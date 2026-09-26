package com.recycle.station.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.recycle.station.common.BizException;
import com.recycle.station.domain.Settlement;
import com.recycle.station.domain.StationDict;
import com.recycle.station.domain.WeighTicket;
import com.recycle.station.dto.Reqs;
import com.recycle.station.dto.Views;
import com.recycle.station.repository.SettlementRepository;
import com.recycle.station.repository.WeighTicketRepository;

/**
 * 模块四：结算单（磅单 → 付款）。
 * <p>
 * 复称闭环接入后，结算有三条硬规矩：
 * <ol>
 *   <li>只认磅单的"当前有效版本金额"（effective_total_amount + active_version_no），
 *       复称放行后旧页面上看到的旧金额提交会被判 VERSION_CONFLICT；</li>
 *   <li>复称冻结中的磅单一律不能付款（frozen_flag），开着旧页面也绕不过；</li>
 *   <li>整笔结算在磅单行锁（FOR UPDATE）内完成，与复称发起互斥，
 *       并发时只有一条链路成功，不会出现钱已付出而复称仍待处理。</li>
 * </ol>
 */
@Service
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final WeighTicketRepository ticketRepository;

    public SettlementService(SettlementRepository settlementRepository, WeighTicketRepository ticketRepository) {
        this.settlementRepository = settlementRepository;
        this.ticketRepository = ticketRepository;
    }

    @Transactional(readOnly = true)
    public List<Views.SettlementView> list() {
        List<Settlement> all = settlementRepository.findAll();
        List<Settlement> sorted = new ArrayList<>(all);
        sorted.sort((a, b) -> b.getSettleTime().compareTo(a.getSettleTime()));
        List<Views.SettlementView> rows = new ArrayList<>();
        for (Settlement s : sorted) {
            rows.add(toView(s));
        }
        return rows;
    }

    /**
     * 给一张待结算磅单付款：实付金额默认等于磅单当前有效版本金额，允许在
     * {@link StationDict#ROUND_TOLERANCE} 以内抹零，超过就拦。
     */
    @Transactional
    public Views.SettlementView settle(Reqs.SettleReq req) {
        String ticketNo = req.ticketNo().trim();

        // 行锁：复称发起若恰好并发，两条链路在此串行，只有一条能成功
        WeighTicket ticket = ticketRepository.findByTicketNoForUpdate(ticketNo);
        if (ticket == null) {
            throw new BizException("磅单 " + ticketNo + " 不存在");
        }
        if ("SETTLED".equals(ticket.getStatus())) {
            throw new BizException("磅单已结算，不要重复打款");
        }
        if ("VOID".equals(ticket.getStatus())) {
            throw new BizException(BizException.TICKET_STATUS_DENIED, "磅单已作废，不能结算");
        }

        // 复称冻结：付款人员即使早就打开了结算弹窗也在此被拦下
        if (ticket.isFrozenFlag()) {
            throw new BizException(BizException.REWEIGH_IN_PROGRESS,
                    "磅单正在复称处理中，结算已冻结：" + ticket.getFrozenReason());
        }

        // 数据版本校验：弹窗是复称放行前打开的，提交时金额版本已变 → 必须刷新按新金额结算
        BigDecimal currentAmount = ticket.getEffectiveTotalAmount().setScale(2, RoundingMode.HALF_UP);
        if (req.expectedVersion() != null && !req.expectedVersion().equals(ticket.getActiveVersionNo())) {
            throw new BizException(BizException.VERSION_CONFLICT,
                    "数据版本已变化：该磅单已由复称产生 V" + ticket.getActiveVersionNo()
                            + " 新金额版本（应付 " + currentAmount + " 元），请刷新后按新版本结算");
        }
        if (req.expectedAmount() != null
                && req.expectedAmount().setScale(2, RoundingMode.HALF_UP).compareTo(currentAmount) != 0) {
            throw new BizException(BizException.VERSION_CONFLICT,
                    "数据版本已变化：应付金额已从 " + req.expectedAmount() + " 元变为 " + currentAmount
                            + " 元（复称重量版本已更新），请刷新后再结算");
        }

        String payMode = req.payMode() == null || req.payMode().isBlank() ? "CASH" : req.payMode().trim();
        if (!StationDict.isValidPayMode(payMode)) {
            throw new BizException("结算方式只能是 CASH / TRANSFER / DEDUCT");
        }

        BigDecimal payable = currentAmount;
        BigDecimal paid = req.paidAmount() == null ? payable : req.paidAmount().setScale(2, RoundingMode.HALF_UP);
        if (paid.compareTo(BigDecimal.ZERO) < 0 || paid.compareTo(payable) > 0) {
            throw new BizException("实付金额必须在 0 到应付金额 " + payable + " 元之间");
        }
        if (payable.subtract(paid).compareTo(StationDict.ROUND_TOLERANCE) > 0) {
            throw new BizException("抹零不能超过 " + StationDict.ROUND_TOLERANCE + " 元，超出请走站长审批");
        }
        if ("DEDUCT".equals(payMode) && paid.compareTo(BigDecimal.ZERO) != 0) {
            throw new BizException("抵扣结算的实付金额必须是 0，差额走往来挂账");
        }
        if (!"DEDUCT".equals(payMode) && paid.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("现金 / 转账结算的实付金额必须大于 0");
        }
        if (req.operator() == null || req.operator().isBlank()) {
            throw new BizException("操作员不能为空");
        }

        LocalDateTime now = LocalDateTime.now();
        Settlement settlement = new Settlement();
        settlement.setSettleNo(nextSettleNo(now));
        settlement.setTicketNo(ticket.getTicketNo());
        settlement.setCustomerName(ticket.getCustomerName());
        settlement.setPayMode(payMode);
        settlement.setPayableAmount(payable);
        settlement.setPaidAmount(paid);
        // 明确这张结算单付的是哪个金额版本
        settlement.setAmountVersion(ticket.getActiveVersionNo());
        settlement.setSettleTime(now);
        settlement.setOperator(req.operator().trim());
        settlement.setRemark(req.remark());
        settlement = settlementRepository.save(settlement);

        ticket.setStatus("SETTLED");
        ticketRepository.save(ticket);

        return toView(settlement);
    }

    /** 结算单号：JS + yyyyMMdd + 四位当日序号 */
    private String nextSettleNo(LocalDateTime now) {
        String base = Settlement.NO_PREFIX + now.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        long seq = settlementRepository.count() + 1;
        String candidate = base + String.format("%04d", seq);
        for (int guard = 0; settlementRepository.findBySettleNo(candidate) != null && guard < 900; guard += 1) {
            candidate = base + String.format("%04d", seq + guard + 1);
        }
        return candidate;
    }

    /** 当天已结算金额（首页指标卡片用） */
    @Transactional(readOnly = true)
    public BigDecimal todaySettledAmount() {
        LocalDateTime from = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        LocalDateTime to = LocalDateTime.of(LocalDate.now(), LocalTime.MAX);
        BigDecimal sum = BigDecimal.ZERO;
        for (Settlement s : settlementRepository.findAll()) {
            if (!s.getSettleTime().isBefore(from) && !s.getSettleTime().isAfter(to)) {
                sum = sum.add(s.getPaidAmount() == null ? BigDecimal.ZERO : s.getPaidAmount());
            }
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    public Views.SettlementView toView(Settlement s) {
        BigDecimal payable = s.getPayableAmount() == null ? BigDecimal.ZERO : s.getPayableAmount();
        BigDecimal paid = s.getPaidAmount() == null ? BigDecimal.ZERO : s.getPaidAmount();
        Integer version = s.getAmountVersion() == null ? 1 : s.getAmountVersion();
        return new Views.SettlementView(s.getId(), s.getSettleNo(), s.getTicketNo(), s.getCustomerName(),
                s.getPayMode(), StationDict.payModeName(s.getPayMode()), payable, paid, payable.subtract(paid),
                version, s.getSettleTime(), s.getOperator(), s.getRemark());
    }
}
