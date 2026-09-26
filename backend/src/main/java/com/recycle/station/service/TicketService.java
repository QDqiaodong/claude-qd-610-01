package com.recycle.station.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.recycle.station.common.BizException;
import com.recycle.station.domain.RecycleCategory;
import com.recycle.station.domain.StationDict;
import com.recycle.station.domain.WeighTicket;
import com.recycle.station.domain.WeighTicketLine;
import com.recycle.station.dto.Reqs;
import com.recycle.station.dto.Views;
import com.recycle.station.repository.RecycleCategoryRepository;
import com.recycle.station.repository.SettlementRepository;
import com.recycle.station.repository.WeighTicketRepository;

/**
 * 模块二：过磅单（主表 + 明细行一起存取）。
 * <p>
 * 明细行的插入依托 {@code @IdClass} 复合主键：行号由服务端从 1 连续编排，
 * 行上的 {@code ticket_no} 既是主键列，又是通过
 * {@code @JoinColumn(name="ticket_no", referencedColumnName="ticket_no")} 指回主单的外键列。
 */
@Service
public class TicketService {

    /** 明细合计与净重之间允许的地磅读数容差（复称重分配同口径复用） */
    public static final BigDecimal WEIGHT_TOLERANCE = BigDecimal.valueOf(0.5);

    private static final List<DateTimeFormatter> TIME_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ISO_DATE_TIME);

    private final WeighTicketRepository ticketRepository;
    private final RecycleCategoryRepository categoryRepository;
    private final SettlementRepository settlementRepository;

    public TicketService(WeighTicketRepository ticketRepository,
                         RecycleCategoryRepository categoryRepository,
                         SettlementRepository settlementRepository) {
        this.ticketRepository = ticketRepository;
        this.categoryRepository = categoryRepository;
        this.settlementRepository = settlementRepository;
    }

    @Transactional(readOnly = true)
    public List<Views.TicketView> list() {
        List<Views.TicketView> rows = new ArrayList<>();
        for (WeighTicket ticket : ticketRepository.findTop30ByOrderByWeighTimeDesc()) {
            rows.add(toView(ticket));
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public List<Views.TicketView> pending() {
        List<Views.TicketView> rows = new ArrayList<>();
        // 复称冻结中的磅单不能进付款台选单
        for (WeighTicket ticket : ticketRepository.findByStatusAndFrozenFlagFalseOrderByWeighTimeDesc("PENDING")) {
            rows.add(toView(ticket));
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public Views.TicketView detail(String ticketNo) {
        WeighTicket ticket = ticketRepository.findByTicketNo(ticketNo);
        if (ticket == null) {
            throw new BizException("磅单 " + ticketNo + " 不存在");
        }
        return toView(ticket);
    }

    /**
     * 开一张过磅单：毛重 - 皮重 = 净重，净重按品类拆进明细行，行内的单价一律以当日牌价重算。
     */
    @Transactional
    public Views.TicketView create(Reqs.TicketCreateReq req) {
        String customerType = req.customerType() == null || req.customerType().isBlank()
                ? "PERSONAL" : req.customerType().trim();
        if (!StationDict.isValidCustomerType(customerType)) {
            throw new BizException("客户类型只能是 PERSONAL（个人）或 MERCHANT（商户）");
        }

        BigDecimal gross = nz(req.grossWeight());
        BigDecimal tare = nz(req.tareWeight());
        if (gross.compareTo(BigDecimal.ZERO) <= 0 || tare.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("毛重必须大于 0，皮重不能为负");
        }
        if (gross.compareTo(tare) <= 0) {
            throw new BizException("毛重（" + gross + "）必须大于皮重（" + tare + "），否则这台车等于没拉东西");
        }

        List<Reqs.LineReq> lines = req.lines() == null ? List.of() : req.lines();
        if (lines.isEmpty()) {
            throw new BizException("请至少录入一行明细：一行一个品类");
        }
        if (lines.size() > StationDict.MAX_LINES) {
            throw new BizException("一张磅单最多 " + StationDict.MAX_LINES + " 行明细，请拆单");
        }

        LocalDateTime weighTime = parseTime(req.weighTime());
        LocalDate statDate = weighTime.toLocalDate();
        String customerName = req.customerName().trim();
        long customerSameDay = ticketRepository.countByStatDateAndCustomerName(statDate, customerName);
        if (customerSameDay >= StationDict.MAX_TICKET_PER_CUSTOMER_DAY) {
            throw new BizException("客户「" + customerName + "」当日已开 "
                    + StationDict.MAX_TICKET_PER_CUSTOMER_DAY + " 张磅单，超出单日上限");
        }

        BigDecimal netWeight = gross.subtract(tare).setScale(2, RoundingMode.HALF_UP);

        WeighTicket ticket = new WeighTicket();
        ticket.setTicketNo(nextTicketNo(statDate));
        ticket.setCustomerName(customerName);
        ticket.setCustomerType(customerType);
        ticket.setWeighTime(weighTime);
        ticket.setStatDate(statDate);
        ticket.setPlateNo(req.plateNo() == null ? "" : req.plateNo().trim());
        ticket.setGrossWeight(gross.setScale(2, RoundingMode.HALF_UP));
        ticket.setTareWeight(tare.setScale(2, RoundingMode.HALF_UP));
        ticket.setNetWeight(netWeight);
        ticket.setStatus("PENDING");
        ticket.setRemark(req.remark());
        ticket.setCreatedAt(LocalDateTime.now());
        // 复称闭环：建档时 V1 即为有效版本，有效口径 = 原始口径（随后按明细合计再刷一次金额）
        ticket.setFrozenFlag(false);
        ticket.setActiveVersionNo(1);

        BigDecimal linesWeight = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        int lineNo = 0;
        for (Reqs.LineReq lineReq : lines) {
            lineNo += 1;
            RecycleCategory category = categoryRepository.findByCategoryNo(lineReq.categoryNo());
            if (category == null) {
                throw new BizException("第 " + lineNo + " 行的品类编号 " + lineReq.categoryNo() + " 不存在");
            }
            if (!Boolean.TRUE.equals(category.getStored())) {
                throw new BizException("品类「" + category.getCategoryName() + "」当天已停收，不能开票");
            }
            BigDecimal weight = nz(lineReq.weight());
            if (weight.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BizException("第 " + lineNo + " 行的重量必须大于 0");
            }
            if (weight.compareTo(StationDict.MAX_LINE_WEIGHT) > 0) {
                throw new BizException("第 " + lineNo + " 行的重量超过单行上限 "
                        + StationDict.MAX_LINE_WEIGHT + "，多半是磅秤读数填错了");
            }
            weight = weight.setScale(2, RoundingMode.HALF_UP);
            BigDecimal unitPrice = category.getBuyPrice();
            BigDecimal subtotal = weight.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);

            WeighTicketLine line = new WeighTicketLine();
            line.setTicketNo(ticket.getTicketNo());
            line.setLineNo(lineNo);
            line.setTicket(ticket);
            line.setCategory(category);
            line.setCategoryNo(category.getCategoryNo());
            line.setCategoryName(category.getCategoryName());
            line.setUnit(category.getUnit());
            line.setWeight(weight);
            line.setUnitPrice(unitPrice);
            line.setSubtotal(subtotal);
            ticket.getLines().add(line);

            linesWeight = linesWeight.add(weight);
            totalAmount = totalAmount.add(subtotal);
        }

        // 明细重量合计不能超过净重：超出的部分说明没扣杂，不允许多算重量去开钱
        if (linesWeight.compareTo(netWeight.add(WEIGHT_TOLERANCE)) > 0) {
            throw new BizException("明细重量合计 " + linesWeight + " 已超过净重 " + netWeight + "，请核对扣杂后再提交");
        }

        ticket.setTotalAmount(totalAmount.setScale(2, RoundingMode.HALF_UP));
        // 有效口径初始等于原始口径
        ticket.setEffectiveNetWeight(netWeight);
        ticket.setEffectiveTotalAmount(ticket.getTotalAmount());
        return toView(ticketRepository.save(ticket));
    }

    /** 状态流转：待结算 → 已结算 / 作废，已结算 → 作废 */
    @Transactional
    public Views.TicketView changeStatus(String ticketNo, String target) {
        // 作废等状态流转同样锁行：复称冻结中的磅单不得通过这里绕过冻结
        WeighTicket ticket = ticketRepository.findByTicketNoForUpdate(ticketNo);
        if (ticket == null) {
            throw new BizException("磅单 " + ticketNo + " 不存在");
        }
        if ("VOID".equals(target) && ticket.isFrozenFlag()) {
            throw new BizException(BizException.REWEIGH_IN_PROGRESS,
                    "磅单正在复称处理中，结算与作废均已冻结：" + ticket.getFrozenReason());
        }
        if (!StationDict.ticketNextStatuses(ticket.getStatus()).contains(target)) {
            throw new BizException(BizException.TICKET_STATUS_DENIED,
                    "磅单当前是「" + StationDict.ticketStatusName(ticket.getStatus())
                            + "」，不能直接改成「" + StationDict.ticketStatusName(target) + "」");
        }
        if ("VOID".equals(target) && "SETTLED".equals(ticket.getStatus())
                && !settlementRepository.findByTicketNoOrderBySettleTimeDesc(ticketNo).isEmpty()) {
            throw new BizException("这张磅单已经结算出过款，作废前请先冲红结算单");
        }
        ticket.setStatus(target);
        return toView(ticketRepository.save(ticket));
    }

    /** 当日流水号：WB + yyyyMMdd + 三位序号；序号撞号就往后顺延 */
    private String nextTicketNo(LocalDate statDate) {
        String base = WeighTicket.NO_PREFIX + statDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        long seq = ticketRepository.countByStatDate(statDate) + 1;
        String candidate = base + String.format("%03d", seq);
        for (int guard = 0; ticketRepository.findByTicketNo(candidate) != null && guard < 900; guard += 1) {
            candidate = base + String.format("%03d", seq + guard + 1);
        }
        return candidate;
    }

    private LocalDateTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return LocalDateTime.now();
        }
        String text = raw.trim().replace(' ', 'T');
        for (DateTimeFormatter formatter : TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(text, formatter);
            } catch (DateTimeParseException ignored) {
                // 换下一种格式再试
            }
        }
        // 只给日期的情况：落到当天 08:00（早班开磅时间）
        try {
            return LocalDateTime.of(LocalDate.parse(raw.trim()), LocalTime.of(8, 0));
        } catch (DateTimeParseException e) {
            throw new BizException("过磅时间格式不对，示例：2026-09-22 09:30");
        }
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public Views.TicketView toView(WeighTicket ticket) {
        List<Views.LineView> lines = new ArrayList<>();
        List<WeighTicketLine> raw = new ArrayList<>(ticket.getLines());
        raw.sort(Comparator.comparingInt(WeighTicketLine::getLineNo));
        for (WeighTicketLine line : raw) {
            lines.add(new Views.LineView(line.getLineNo(), line.getCategoryNo(), line.getCategoryName(),
                    line.getUnit(), StationDict.unitName(line.getUnit()),
                    line.getWeight(), line.getUnitPrice(), line.getSubtotal()));
        }
        Map<String, String> statusName = new LinkedHashMap<>();
        for (String next : StationDict.ticketNextStatuses(ticket.getStatus())) {
            statusName.put(next, StationDict.ticketStatusName(next));
        }
        List<Map<String, String>> nextStatuses = new ArrayList<>();
        statusName.forEach((k, v) -> nextStatuses.add(Map.of("value", k, "label", v)));
        String settleNo = settlementRepository.findByTicketNoOrderBySettleTimeDesc(ticket.getTicketNo())
                .stream().findFirst().map(s -> s.getSettleNo()).orElse("");
        return new Views.TicketView(
                ticket.getId(), ticket.getTicketNo(), ticket.getCustomerName(), ticket.getCustomerType(),
                StationDict.customerTypeName(ticket.getCustomerType()), ticket.getWeighTime(), ticket.getStatDate(),
                ticket.getPlateNo(), ticket.getGrossWeight(), ticket.getTareWeight(), ticket.getNetWeight(),
                ticket.getTotalAmount(), ticket.getStatus(), StationDict.ticketStatusName(ticket.getStatus()),
                ticket.getRemark(), ticket.getCreatedAt(), lines, nextStatuses, settleNo,
                ticket.isFrozenFlag(), ticket.getFrozenReason(), ticket.getFrozenReweighNo(),
                ticket.getActiveVersionNo(), ticket.getEffectiveNetWeight(), ticket.getEffectiveTotalAmount());
    }
}
