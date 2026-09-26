package com.recycle.station.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.recycle.station.common.BizException;
import com.recycle.station.domain.ReweighEvent;
import com.recycle.station.domain.ReweighLine;
import com.recycle.station.domain.ReweighOrder;
import com.recycle.station.domain.StationDict;
import com.recycle.station.domain.WeighTicket;
import com.recycle.station.domain.WeighTicketLine;
import com.recycle.station.domain.WeighTicketVersion;
import com.recycle.station.domain.WeighTicketVersionLine;
import com.recycle.station.dto.Reqs;
import com.recycle.station.dto.Views;
import com.recycle.station.repository.ReweighOrderRepository;
import com.recycle.station.repository.WeighTicketLineRepository;
import com.recycle.station.repository.WeighTicketRepository;
import com.recycle.station.repository.WeighTicketVersionLineRepository;
import com.recycle.station.repository.WeighTicketVersionRepository;

/**
 * 模块六：复称复核台。
 * <p>
 * 一条完整链路：发起（立即冻结结算）→ 补录三项重量与明细重分配 →
 * 差异在双阈值内由另一名当班人员现场放行（否则进待复核）→ 复核通过/驳回 →
 * 通过则生成新的有效重量版本并解冻，驳回则保留驳回原因、回指原版本并解冻。
 * <p>
 * 并发底线（全部由数据库保证，重启不丢）：
 * <ul>
 *   <li>发起与结算都走 {@code SELECT ... FOR UPDATE} 锁磅单行，串行化，只有一条链路成功；</li>
 *   <li>uk_open_reweigh 部分唯一索引保证同一磅单至多一条未结束复称；</li>
 *   <li>(client_request_id, initiator) 唯一索引 + 先查后插保证重试幂等；</li>
 *   <li>磅单与复称单都带 JPA 乐观锁，旧页面 / 双窗口改判会撞 VERSION_CONFLICT。</li>
 * </ul>
 */
@Service
public class ReweighService {

    private static final BigDecimal WEIGHT_TOLERANCE = TicketService.WEIGHT_TOLERANCE;

    private final ReweighOrderRepository reweighRepository;
    private final WeighTicketRepository ticketRepository;
    private final WeighTicketLineRepository lineRepository;
    private final WeighTicketVersionRepository versionRepository;
    private final WeighTicketVersionLineRepository versionLineRepository;

    public ReweighService(ReweighOrderRepository reweighRepository,
                          WeighTicketRepository ticketRepository,
                          WeighTicketLineRepository lineRepository,
                          WeighTicketVersionRepository versionRepository,
                          WeighTicketVersionLineRepository versionLineRepository) {
        this.reweighRepository = reweighRepository;
        this.ticketRepository = ticketRepository;
        this.lineRepository = lineRepository;
        this.versionRepository = versionRepository;
        this.versionLineRepository = versionLineRepository;
    }

    // ================================================================
    // 查询
    // ================================================================

    @Transactional(readOnly = true)
    public List<Views.ReweighView> board() {
        List<Views.ReweighView> rows = new ArrayList<>();
        for (ReweighOrder order : reweighRepository.findAllForBoard()) {
            rows.add(toView(order, null));
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public Views.ReweighView detail(String reweighNo) {
        ReweighOrder order = reweighRepository.findByReweighNo(reweighNo.trim());
        if (order == null) {
            throw new BizException("复称单 " + reweighNo + " 不存在");
        }
        return toView(order, null);
    }

    /** 磅单维度的复称/版本信息（过磅台、结算台、磅单详情共用） */
    @Transactional(readOnly = true)
    public Views.TicketReweighView ticketReweighView(String ticketNo) {
        WeighTicket ticket = ticketRepository.findByTicketNo(ticketNo);
        if (ticket == null) {
            throw new BizException("磅单 " + ticketNo + " 不存在");
        }
        return buildTicketReweighView(ticket);
    }

    // ================================================================
    // 1. 发起复称
    // ================================================================

    /**
     * 发起复称。
     * <p>
     * 关键顺序（持有磅单行锁的同一事务内）：
     * 校验磅单是 PENDING → 幂等查重 → 未结束复称查重 → 建复称单（拷贝原始快照）→
     * 置冻结标记 → 追加轨迹。结算事务拿不到同一行锁就得等，提交后只能看到 SETTLED，
     * 从而保证"复称发起"和"结算确认"恰并发时只有一条成功。
     */
    @Transactional
    public Views.ReweighView initiate(Reqs.ReweighInitiateReq req) {
        String ticketNo = req.ticketNo().trim();
        String initiator = req.initiator().trim();

        // 幂等：重复点击 / 请求超时重试（同一发起人同一请求键）→ 回查已落库的那一条，返回同一结果
        String clientRequestId = normalizeClientRequestId(req.clientRequestId());
        if (clientRequestId != null) {
            ReweighOrder existing = reweighRepository.findByClientRequestIdAndInitiator(clientRequestId, initiator);
            if (existing != null) {
                return toView(existing, null);
            }
        }

        // 行锁：与结算确认互斥
        WeighTicket ticket = ticketRepository.findByTicketNoForUpdate(ticketNo);
        if (ticket == null) {
            throw new BizException("磅单 " + ticketNo + " 不存在");
        }

        // 拿到锁后二次幂等检查：两个窗口同请求键并发，后一个等锁期间前一个可能已经提交
        if (clientRequestId != null) {
            ReweighOrder existing = reweighRepository.findByClientRequestIdAndInitiator(clientRequestId, initiator);
            if (existing != null) {
                return toView(existing, ticket);
            }
        }

        // 只有待结算磅单可以发起；已结算 / 已作废不得倒退。
        // 若在等锁期间结算抢先提交，这里给出专属错误码 SETTLEMENT_RACED。
        if ("SETTLED".equals(ticket.getStatus())) {
            throw new BizException(BizException.SETTLEMENT_RACED,
                    "结算已经抢先完成：磅单 " + ticketNo + " 已付款结算，复称不能再发起");
        }
        if ("VOID".equals(ticket.getStatus())) {
            throw new BizException(BizException.TICKET_STATUS_DENIED,
                    "磅单已作废，不能发起复称");
        }
        if (!"PENDING".equals(ticket.getStatus())) {
            throw new BizException(BizException.TICKET_STATUS_DENIED,
                    "磅单当前是「" + StationDict.ticketStatusName(ticket.getStatus())
                            + "」，只有待结算磅单才能发起复称");
        }
        if (ticket.isFrozenFlag() || reweighRepository.countByTicketNoAndOpenFlagTrue(ticketNo) > 0) {
            ReweighOrder open = reweighRepository.findByTicketNoAndOpenFlagTrue(ticketNo).stream()
                    .findFirst().orElse(null);
            String no = open == null ? "" : open.getReweighNo();
            throw new BizException(BizException.REWEIGH_IN_PROGRESS,
                    "已有复称处理中：磅单 " + ticketNo + " 存在未结束的复称单 " + no
                            + "，请处理完或驳回后再发起");
        }

        String reason = req.diffReason() == null ? "" : req.diffReason().trim();
        if (reason.length() > StationDict.REWEIGH_REASON_MAX) {
            throw new BizException("差异原因不能超过 " + StationDict.REWEIGH_REASON_MAX + " 字");
        }

        LocalDateTime now = LocalDateTime.now();
        ReweighOrder order = new ReweighOrder();
        order.setReweighNo(nextReweighNo(now));
        order.setTicketNo(ticketNo);
        // 原始快照：毛/皮/净、原金额全部原样留痕，后续任何流程都不得改写磅单上的这些列
        order.setOriginGrossWeight(ticket.getGrossWeight());
        order.setOriginTareWeight(ticket.getTareWeight());
        order.setOriginNetWeight(ticket.getNetWeight());
        order.setOriginTotalAmount(ticket.getTotalAmount());
        order.setDiffReason(reason.isEmpty() ? null : reason);
        order.setStatus("INITIATED");
        order.setInitiator(initiator);
        order.setInitiatedAt(now);
        order.setClientRequestId(clientRequestId);
        order.setOpenFlag(true);
        order.setCreatedAt(now);

        // 明细快照：品类、单位、原数量、原单价全部拷贝（后续重算只准用这份单价）
        List<WeighTicketLine> originLines = lineRepository.findByTicketNoOrderByLineNoAsc(ticketNo);
        for (WeighTicketLine src : originLines) {
            ReweighLine line = new ReweighLine();
            line.setReweigh(order);
            line.setLineNo(src.getLineNo());
            line.setCategoryNo(src.getCategoryNo());
            line.setCategoryName(src.getCategoryName());
            line.setUnit(src.getUnit());
            line.setOriginWeight(src.getWeight());
            line.setUnitPrice(src.getUnitPrice());
            order.getLines().add(line);
        }

        // 允许一次录全三项复称重量（现场直接复完）：落重量并做阈值判定，但明细仍须走补录确认重分配
        boolean weightsComplete = req.reGrossWeight() != null && req.reTareWeight() != null;
        if (weightsComplete || req.reNetWeight() != null) {
            applyReWeights(order, req.reGrossWeight(), req.reTareWeight(), req.reNetWeight());
            order.setStatus(order.getDiffWithinThreshold() ? "AWAIT_RELEASE" : "AWAIT_REVIEW");
            order.setSupplementedAt(now);
        }

        order = reweighRepository.save(order);

        // 立即冻结结算：付款人员就算开着旧页面，提交时也会被冻结 + 行锁拦下
        ticket.setFrozenFlag(true);
        ticket.setFrozenReweighNo(order.getReweighNo());
        ticket.setFrozenReason("复称处理中（" + order.getReweighNo() + "，发起人 " + initiator
                + "）：复称未放行/复核前暂停结算");
        ticketRepository.save(ticket);

        addEvent(order, ticketNo, "INITIATED", null, order.getStatus(), initiator,
                reason.isEmpty() ? "发起复称，结算已冻结" : "发起复称：" + reason, now);
        addEvent(order, ticketNo, "SETTLEMENT_FROZEN", null, null, initiator,
                "复称 " + order.getReweighNo() + " 发起，磅单结算冻结", now);
        if (order.getSupplementedAt() != null) {
            addEvent(order, ticketNo, "SUPPLEMENTED", "INITIATED", order.getStatus(), initiator,
                    "发起时已录全三项复称重量", now);
        }
        reweighRepository.save(order);

        return toView(order, ticket);
    }

    // ================================================================
    // 2. 补录复称数据与明细重分配
    // ================================================================

    @Transactional
    public Views.ReweighView supplement(Reqs.ReweighSupplementReq req) {
        ReweighOrder order = lockReweigh(req.reweighNo());
        String operator = initiatorOf(order);

        if (StationDict.isReweighClosed(order.getStatus())) {
            // 已终审记录不得改判/补录
            throw new BizException(BizException.TICKET_STATUS_DENIED,
                    "复称单已" + StationDict.reweighStatusName(order.getStatus()) + "，不能再补录");
        }
        // 一旦差异超阈值进入待复核，就不允许再通过改重量退回现场放行（防止绕过复核）
        if ("AWAIT_REVIEW".equals(order.getStatus())) {
            throw new BizException(BizException.TICKET_STATUS_DENIED,
                    "该复称差异已超阈值进入待复核，不能再修改复称数据；请等待复核结论，或由复核驳回后重新发起复称");
        }

        applyReWeights(order, req.reGrossWeight(), req.reTareWeight(), req.reNetWeight());

        String reason = req.diffReason() == null ? "" : req.diffReason().trim();
        if (reason.isEmpty()) {
            throw new BizException("请填写差异原因（扣杂争议 / 地磅误差 / 车辆复称等）");
        }
        if (reason.length() > StationDict.REWEIGH_REASON_MAX) {
            throw new BizException("差异原因不能超过 " + StationDict.REWEIGH_REASON_MAX + " 字");
        }
        order.setDiffReason(reason);

        // 明细重新分配：沿用原品类/单位/单价快照，逐行按行号对位，只改数量
        Map<Integer, ReweighLine> byLineNo = new HashMap<>();
        for (ReweighLine line : order.getLines()) {
            byLineNo.put(line.getLineNo(), line);
        }
        Set<Integer> submitted = new HashSet<>();
        BigDecimal newTotal = BigDecimal.ZERO;
        BigDecimal newAmount = BigDecimal.ZERO;
        for (Reqs.ReweighLineReq lineReq : req.lines()) {
            ReweighLine line = byLineNo.get(lineReq.lineNo());
            if (line == null) {
                throw new BizException("第 " + lineReq.lineNo() + " 行在原磅单中不存在，不能新增明细行");
            }
            if (!submitted.add(lineReq.lineNo())) {
                throw new BizException("第 " + lineReq.lineNo() + " 行重复提交了，请合并为一行");
            }
            BigDecimal newWeight = lineReq.newWeight().setScale(2, RoundingMode.HALF_UP);
            if (newWeight.compareTo(BigDecimal.ZERO) < 0) {
                throw new BizException("第 " + lineReq.lineNo() + " 行的重分配数量不能为负");
            }
            BigDecimal subtotal = newWeight.multiply(line.getUnitPrice()).setScale(2, RoundingMode.HALF_UP);
            line.setNewWeight(newWeight);
            line.setSubtotal(subtotal);
            newTotal = newTotal.add(newWeight);
            newAmount = newAmount.add(subtotal);
        }
        if (submitted.size() != order.getLines().size()) {
            throw new BizException("请对原磅单的每一行明细都给出重分配数量（可为 0，但不能缺行）");
        }

        // 重分配合计不得超过复称净重（混合单位时按数值合计，与建档同口径，留 0.5 容差）
        BigDecimal cap = order.getReNetWeight();
        if (newTotal.compareTo(cap.add(WEIGHT_TOLERANCE)) > 0) {
            throw new BizException("重新分配后的明细合计 " + newTotal + " 超过复称净重 " + cap
                    + "，请核对后再提交");
        }

        boolean within = StationDict.diffWithinReleaseThreshold(order.getOriginNetWeight(),
                order.getDiffNetWeight());
        order.setDiffWithinThreshold(within);
        String from = order.getStatus();
        String to = within ? "AWAIT_RELEASE" : "AWAIT_REVIEW";
        order.setStatus(to);
        order.setSupplementedAt(LocalDateTime.now());
        order = reweighRepository.save(order);

        addEvent(order, order.getTicketNo(), "SUPPLEMENTED", from, to, operator,
                "补录三项重量与明细重分配：复称净重 " + order.getReNetWeight()
                        + "，差异 " + order.getDiffNetWeight() + "（" + reason + "）"
                        + (within ? "，差异在阈值内，待另一名当班人员现场放行"
                                  : "，差异超出阈值，必须进入待复核"),
                LocalDateTime.now());
        reweighRepository.save(order);

        return toView(order, null);
    }

    // ================================================================
    // 3a. 现场放行（差异在双阈值内，另一名当班人员）
    // ================================================================

    @Transactional
    public Views.ReweighView release(Reqs.ReweighReleaseReq req) {
        ReweighOrder order = lockReweigh(req.reweighNo());
        String operator = req.operator().trim();

        if (StationDict.isReweighClosed(order.getStatus())) {
            throw new BizException(BizException.TICKET_STATUS_DENIED,
                    "复称单已" + StationDict.reweighStatusName(order.getStatus()) + "，不能再次改判");
        }
        if ("AWAIT_REVIEW".equals(order.getStatus())) {
            throw new BizException(BizException.TICKET_STATUS_DENIED,
                    "差异超出阈值，该复称必须走复核，不能现场放行");
        }
        if (!"AWAIT_RELEASE".equals(order.getStatus())) {
            throw new BizException(BizException.TICKET_STATUS_DENIED,
                    "复称单还没补录完复称数据，不能放行");
        }
        if (!Boolean.TRUE.equals(order.getDiffWithinThreshold())) {
            // 服务端重新判定，不信任前端传参
            throw new BizException(BizException.TICKET_STATUS_DENIED,
                    "复称净重差异已超出现场放行阈值，必须进入待复核");
        }
        if (operator.equals(order.getInitiator())) {
            throw new BizException(BizException.SELF_REVIEW_FORBIDDEN,
                    "本人无权放行：现场确认必须由发起人以外的另一名当班人员操作（发起人 "
                            + order.getInitiator() + "）");
        }

        LocalDateTime now = LocalDateTime.now();
        order.setStatus("RELEASED");
        order.setReleaser(operator);
        order.setReleasedAt(now);
        order.setOpenFlag(false);
        int versionNo = adoptNewVersionAndUnfreeze(order, operator, now, "RELEASED");
        order.setAdoptedVersionNo(versionNo);
        order = reweighRepository.save(order);

        addEvent(order, order.getTicketNo(), "RELEASED", "AWAIT_RELEASE", "RELEASED", operator,
                "现场确认放行，采用 V" + versionNo + " 重量版本，恢复结算", now);
        addEvent(order, order.getTicketNo(), "SETTLEMENT_RESUMED", null, null, operator,
                "复称放行，磅单恢复结算（有效版本 V" + versionNo + "）", now);
        reweighRepository.save(order);

        return toView(order, null);
    }

    // ================================================================
    // 3b. 复核通过 / 驳回
    // ================================================================

    /**
     * 差异虽在阈值内但现场仍有争议：发起人（或当班人员）升级为待复核，
     * 之后必须由非发起人复核。升级只允许发生在 AWAIT_RELEASE。
     */
    @Transactional
    public Views.ReweighView escalate(Reqs.ReweighEscalateReq req) {
        ReweighOrder order = lockReweigh(req.reweighNo());
        if (!"AWAIT_RELEASE".equals(order.getStatus())) {
            throw new BizException(BizException.TICKET_STATUS_DENIED,
                    "只有待现场放行的复称单才能升级复核（当前："
                            + StationDict.reweighStatusName(order.getStatus()) + "）");
        }
        String reason = req.reason() == null ? "" : req.reason().trim();
        LocalDateTime now = LocalDateTime.now();
        order.setStatus("AWAIT_REVIEW");
        order = reweighRepository.save(order);
        addEvent(order, order.getTicketNo(), "SUPPLEMENTED", "AWAIT_RELEASE", "AWAIT_REVIEW",
                req.operator().trim(),
                "现场对差异仍有争议，升级为待复核" + (reason.isEmpty() ? "" : "：" + reason), now);
        reweighRepository.save(order);
        return toView(order, null);
    }

    @Transactional
    public Views.ReweighView review(Reqs.ReweighReviewReq req) {
        ReweighOrder order = lockReweigh(req.reweighNo());
        String reviewer = req.reviewer().trim();
        String result = req.result() == null ? "" : req.result().trim().toUpperCase();

        if (StationDict.isReweighClosed(order.getStatus())) {
            throw new BizException(BizException.TICKET_STATUS_DENIED,
                    "复称单已" + StationDict.reweighStatusName(order.getStatus()) + "，已通过或驳回的记录不得再次改判");
        }
        if (!"AWAIT_REVIEW".equals(order.getStatus())) {
            throw new BizException(BizException.TICKET_STATUS_DENIED,
                    "只有待复核状态的复称单才能复核（当前："
                            + StationDict.reweighStatusName(order.getStatus()) + "）");
        }
        if (reviewer.equals(order.getInitiator())) {
            throw new BizException(BizException.SELF_REVIEW_FORBIDDEN,
                    "本人无权复核：复核人不能是发起人本人（发起人 " + order.getInitiator() + "）");
        }
        if (!"APPROVED".equals(result) && !"REJECTED".equals(result)) {
            throw new BizException("复核结论只能是 APPROVED（通过）或 REJECTED（驳回）");
        }

        LocalDateTime now = LocalDateTime.now();
        order.setReviewer(reviewer);
        order.setReviewedAt(now);
        order.setReviewResult(result);
        order.setOpenFlag(false);

        if ("REJECTED".equals(result)) {
            String rejectReason = req.rejectReason() == null ? "" : req.rejectReason().trim();
            if (rejectReason.isEmpty()) {
                throw new BizException("驳回时必须填写驳回原因");
            }
            if (rejectReason.length() > StationDict.REWEIGH_REASON_MAX) {
                throw new BizException("驳回原因不能超过 " + StationDict.REWEIGH_REASON_MAX + " 字");
            }
            order.setStatus("REJECTED");
            order.setRejectReason(rejectReason);
            unfreezeTicket(order.getTicketNo());
            order = reweighRepository.save(order);
            addEvent(order, order.getTicketNo(), "REJECTED", "AWAIT_REVIEW", "REJECTED", reviewer,
                    "复核驳回：" + rejectReason + "。磅单沿用原重量与原金额，恢复结算资格", now);
            addEvent(order, order.getTicketNo(), "SETTLEMENT_RESUMED", null, null, reviewer,
                    "复称驳回，磅单恢复结算（有效版本仍为发起前版本）", now);
            reweighRepository.save(order);
            return toView(order, null);
        }

        order.setStatus("APPROVED");
        int versionNo = adoptNewVersionAndUnfreeze(order, reviewer, now, "APPROVED");
        order.setAdoptedVersionNo(versionNo);
        order = reweighRepository.save(order);
        addEvent(order, order.getTicketNo(), "APPROVED", "AWAIT_REVIEW", "APPROVED", reviewer,
                "复核通过，生成并采用 V" + versionNo + " 重量版本，恢复结算", now);
        addEvent(order, order.getTicketNo(), "SETTLEMENT_RESUMED", null, null, reviewer,
                "复称复核通过，磅单恢复结算（有效版本 V" + versionNo + "）", now);
        reweighRepository.save(order);

        return toView(order, null);
    }

    // ================================================================
    // 核心内部逻辑
    // ================================================================

    /**
     * 放行/通过后（持有磅单行锁的同一事务内）：依据复称三项重量与明细重分配生成新的有效版本
     * （不改写任何原始数据），把磅单的有效口径与版本指针切到新版本并解除结算冻结。
     */
    private int adoptNewVersionAndUnfreeze(ReweighOrder order, String operator, LocalDateTime now, String via) {
        if (order.getReNetWeight() == null) {
            throw new BizException("复称三项重量尚未补录，不能生成重量版本");
        }
        WeighTicket ticket = ticketRepository.findByTicketNoForUpdate(order.getTicketNo());
        if (ticket == null) {
            throw new BizException("磅单 " + order.getTicketNo() + " 不存在");
        }
        // 终审瞬间若磅单已被结算链路抢先置为 SETTLED，则不允许再落版本（理论上行锁已互斥，双保险）
        if (!"PENDING".equals(ticket.getStatus())) {
            throw new BizException(BizException.TICKET_STATUS_DENIED,
                    "磅单已不是待结算状态（" + StationDict.ticketStatusName(ticket.getStatus()) + "），复称无法生效");
        }

        // V1 不落版本表；版本号在表行数与当前指针两者上取大，保证多次复称版本号严格递增
        int newVersionNo = Math.max((int) versionRepository.countByTicketNo(order.getTicketNo()) + 1,
                ticket.getActiveVersionNo() + 1);

        BigDecimal totalAmount = BigDecimal.ZERO;
        List<ReweighLine> reLines = new ArrayList<>(order.getLines());
        reLines.sort(Comparator.comparingInt(ReweighLine::getLineNo));
        for (ReweighLine rl : reLines) {
            if (rl.getNewWeight() == null) {
                throw new BizException("第 " + rl.getLineNo() + " 行尚未补录重分配数量");
            }
            totalAmount = totalAmount.add(rl.getSubtotal());
        }
        totalAmount = totalAmount.setScale(2, RoundingMode.HALF_UP);

        // 重分配合计不得超过复称净重（补录时已拦一次，终审前再兜底）
        BigDecimal newTotal = reLines.stream().map(ReweighLine::getNewWeight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (newTotal.compareTo(order.getReNetWeight().add(WEIGHT_TOLERANCE)) > 0) {
            throw new BizException("重新分配后的明细合计 " + newTotal + " 超过复称净重 "
                    + order.getReNetWeight());
        }

        WeighTicketVersion version = new WeighTicketVersion();
        version.setTicketNo(order.getTicketNo());
        version.setVersionNo(newVersionNo);
        version.setSourceReweighNo(order.getReweighNo());
        version.setGrossWeight(order.getReGrossWeight());
        version.setTareWeight(order.getReTareWeight());
        version.setNetWeight(order.getReNetWeight());
        version.setTotalAmount(totalAmount);
        version.setCreatedBy(operator);
        version.setCreatedAt(now);

        for (ReweighLine rl : reLines) {
            WeighTicketVersionLine vl = new WeighTicketVersionLine();
            vl.setVersion(version);
            vl.setTicketNo(order.getTicketNo());
            vl.setVersionNo(newVersionNo);
            vl.setLineNo(rl.getLineNo());
            vl.setCategoryNo(rl.getCategoryNo());
            vl.setCategoryName(rl.getCategoryName());
            vl.setUnit(rl.getUnit());
            vl.setWeight(rl.getNewWeight());
            // 单价沿用原单快照，绝不查 recycle_category 的现行牌价
            vl.setUnitPrice(rl.getUnitPrice());
            vl.setSubtotal(rl.getSubtotal());
            version.getLines().add(vl);
        }
        versionRepository.save(version);

        // 切换磅单有效指针与结算口径并解冻；原始 gross/tare/net/total_amount 列原样不动
        ticket.setActiveVersionNo(newVersionNo);
        ticket.setEffectiveNetWeight(order.getReNetWeight());
        ticket.setEffectiveTotalAmount(totalAmount);
        ticket.setFrozenFlag(false);
        ticket.setFrozenReason(null);
        ticket.setFrozenReweighNo(null);
        ticketRepository.save(ticket);
        return newVersionNo;
    }

    /** 驳回后解冻：复称未结束前不会产生新版本，原始数据从未覆盖，只需解除冻结 */
    private void unfreezeTicket(String ticketNo) {
        WeighTicket ticket = ticketRepository.findByTicketNoForUpdate(ticketNo);
        if (ticket == null) {
            return;
        }
        ticket.setFrozenFlag(false);
        ticket.setFrozenReason(null);
        ticket.setFrozenReweighNo(null);
        ticketRepository.save(ticket);
    }

    /** 校验并落三项复称重量，重算净重与差异、阈值判定 */
    private void applyReWeights(ReweighOrder order, BigDecimal gross, BigDecimal tare, BigDecimal netInput) {
        if (gross == null || tare == null) {
            throw new BizException("复称毛重、复称皮重都必须填写");
        }
        BigDecimal reGross = gross.setScale(2, RoundingMode.HALF_UP);
        BigDecimal reTare = tare.setScale(2, RoundingMode.HALF_UP);
        if (reGross.compareTo(BigDecimal.ZERO) <= 0 || reTare.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("复称毛重必须大于 0，复称皮重不能为负");
        }
        if (reGross.compareTo(reTare) <= 0) {
            throw new BizException("复称毛重（" + reGross + "）必须大于复称皮重（" + reTare + "）");
        }
        BigDecimal reNet = reGross.subtract(reTare).setScale(2, RoundingMode.HALF_UP);
        if (netInput != null) {
            BigDecimal given = netInput.setScale(2, RoundingMode.HALF_UP);
            if (given.subtract(reNet).abs().compareTo(WEIGHT_TOLERANCE) > 0) {
                throw new BizException("填写的复称净重 " + given + " 与毛重-皮重 " + reNet + " 不一致");
            }
        }
        order.setReGrossWeight(reGross);
        order.setReTareWeight(reTare);
        order.setReNetWeight(reNet);
        BigDecimal diff = reNet.subtract(order.getOriginNetWeight()).setScale(2, RoundingMode.HALF_UP);
        order.setDiffNetWeight(diff);
        order.setDiffWithinThreshold(
                StationDict.diffWithinReleaseThreshold(order.getOriginNetWeight(), diff));
    }

    private ReweighOrder lockReweigh(String reweighNo) {
        if (reweighNo == null || reweighNo.isBlank()) {
            throw new BizException("复称单号不能为空");
        }
        ReweighOrder order = reweighRepository.findByReweighNo(reweighNo.trim());
        if (order == null) {
            throw new BizException("复称单 " + reweighNo + " 不存在");
        }
        return order;
    }

    private String initiatorOf(ReweighOrder order) {
        return order.getInitiator();
    }

    private void addEvent(ReweighOrder order, String ticketNo, String type,
                          String from, String to, String operator, String note, LocalDateTime at) {
        ReweighEvent event = new ReweighEvent();
        event.setReweigh(order);
        event.setTicketNo(ticketNo);
        event.setEventType(type);
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setOperator(operator);
        event.setNote(note);
        event.setCreatedAt(at);
        order.getEvents().add(event);
    }

    /** FC + yyyyMMdd + 三位序号（以当日发起的复称单为序号基准，撞号顺延） */
    private String nextReweighNo(LocalDateTime now) {
        String base = ReweighOrder.NO_PREFIX + now.format(DateTimeFormatter.BASIC_ISO_DATE);
        long seq = reweighRepository.count() + 1;
        String candidate = base + String.format("%03d", seq);
        for (int guard = 0; reweighRepository.findByReweighNo(candidate) != null && guard < 900; guard += 1) {
            seq += 1;
            candidate = base + String.format("%03d", seq);
        }
        return candidate;
    }

    private String normalizeClientRequestId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String id = raw.trim();
        return id.length() > 64 ? id.substring(0, 64) : id;
    }

    // ================================================================
    // 视图装配
    // ================================================================

    private Views.ReweighView toView(ReweighOrder order, WeighTicket ticket) {
        WeighTicket t = ticket;
        if (t == null) {
            t = ticketRepository.findByTicketNo(order.getTicketNo());
        }
        List<Views.ReweighLineView> lineViews = new ArrayList<>();
        List<ReweighLine> reLines = new ArrayList<>(order.getLines());
        reLines.sort(Comparator.comparingInt(ReweighLine::getLineNo));
        for (ReweighLine l : reLines) {
            lineViews.add(new Views.ReweighLineView(l.getLineNo(), l.getCategoryNo(), l.getCategoryName(),
                    l.getUnit(), StationDict.unitName(l.getUnit()), l.getOriginWeight(), l.getUnitPrice(),
                    l.getNewWeight(), l.getSubtotal()));
        }
        List<Views.ReweighEventView> eventViews = new ArrayList<>();
        for (ReweighEvent e : order.getEvents()) {
            eventViews.add(new Views.ReweighEventView(e.getId(), e.getEventType(),
                    StationDict.reweighEventName(e.getEventType()), e.getFromStatus(),
                    StationDict.reweighStatusName(e.getFromStatus()), e.getToStatus(),
                    StationDict.reweighStatusName(e.getToStatus()), e.getOperator(), e.getNote(),
                    e.getCreatedAt()));
        }

        String owner;
        String ownerRole;
        String nextAction;
        boolean canAct;
        switch (order.getStatus()) {
            case "INITIATED" -> {
                owner = order.getInitiator();
                ownerRole = "发起人补录";
                nextAction = "SUPPLEMENT";
                canAct = true;
            }
            case "AWAIT_RELEASE" -> {
                owner = "另一名当班人员";
                ownerRole = "待现场放行";
                nextAction = "RELEASE";
                canAct = true;
            }
            case "AWAIT_REVIEW" -> {
                owner = "复核人（非发起人）";
                ownerRole = "待复核";
                nextAction = "REVIEW";
                canAct = true;
            }
            default -> {
                owner = closedOwner(order);
                ownerRole = "已结束";
                nextAction = "NONE";
                canAct = false;
            }
        }

        String customerName = t == null ? "" : t.getCustomerName();
        String customerTypeName = t == null ? "" : StationDict.customerTypeName(t.getCustomerType());
        String plateNo = t == null ? "" : t.getPlateNo();

        return new Views.ReweighView(order.getId(), order.getReweighNo(), order.getTicketNo(), customerName,
                customerTypeName, plateNo,
                order.getOriginGrossWeight(), order.getOriginTareWeight(), order.getOriginNetWeight(),
                order.getOriginTotalAmount(), order.getReGrossWeight(), order.getReTareWeight(),
                order.getReNetWeight(), order.getDiffNetWeight(), order.getDiffWithinThreshold(),
                order.getDiffReason(), order.getStatus(), StationDict.reweighStatusName(order.getStatus()),
                order.getInitiator(), order.getInitiatedAt(), order.getSupplementedAt(), order.getReleaser(),
                order.getReleasedAt(), order.getReviewer(), order.getReviewedAt(), order.getReviewResult(),
                order.getRejectReason(), order.getAdoptedVersionNo(), order.isOpenFlag(),
                owner, ownerRole, canAct, nextAction, order.getLockVersion(), lineViews, eventViews);
    }

    private String closedOwner(ReweighOrder order) {
        return switch (order.getStatus()) {
            case "RELEASED" -> order.getReleaser() == null ? "" : order.getReleaser();
            case "APPROVED", "REJECTED" -> order.getReviewer() == null ? "" : order.getReviewer();
            default -> "";
        };
    }

    private Views.TicketReweighView buildTicketReweighView(WeighTicket ticket) {
        List<ReweighOrder> opens = reweighRepository.findByTicketNoAndOpenFlagTrue(ticket.getTicketNo());
        ReweighOrder open = opens.stream().findFirst().orElse(null);

        List<Views.LineView> lineViews = new ArrayList<>();
        for (WeighTicketLine l : lineRepository.findByTicketNoOrderByLineNoAsc(ticket.getTicketNo())) {
            lineViews.add(new Views.LineView(l.getLineNo(), l.getCategoryNo(), l.getCategoryName(),
                    l.getUnit(), StationDict.unitName(l.getUnit()), l.getWeight(), l.getUnitPrice(),
                    l.getSubtotal()));
        }

        List<Views.TicketVersionView> versions = new ArrayList<>();
        for (WeighTicketVersion v : versionRepository
                .findByTicketNoOrderByVersionNoAsc(ticket.getTicketNo())) {
            List<Views.LineView> vLines = new ArrayList<>();
            for (WeighTicketVersionLine vl :
                    versionLineRepository.findByTicketNoAndVersionNoOrderByLineNoAsc(
                            ticket.getTicketNo(), v.getVersionNo())) {
                vLines.add(new Views.LineView(vl.getLineNo(), vl.getCategoryNo(), vl.getCategoryName(),
                        vl.getUnit(), StationDict.unitName(vl.getUnit()), vl.getWeight(), vl.getUnitPrice(),
                        vl.getSubtotal()));
            }
            versions.add(new Views.TicketVersionView(v.getVersionNo(), v.getSourceReweighNo(),
                    v.getGrossWeight(), v.getTareWeight(), v.getNetWeight(), v.getTotalAmount(),
                    v.getCreatedBy(), v.getCreatedAt(), vLines));
        }

        String currentOwner = null;
        if (open != null) {
            currentOwner = switch (open.getStatus()) {
                case "INITIATED" -> open.getInitiator() + "（发起人补录）";
                case "AWAIT_RELEASE" -> "另一名当班人员（现场放行）";
                case "AWAIT_REVIEW" -> "复核人（不得是发起人）";
                default -> "";
            };
        }

        return new Views.TicketReweighView(ticket.getTicketNo(), ticket.getCustomerName(),
                StationDict.customerTypeName(ticket.getCustomerType()), ticket.getPlateNo(),
                ticket.getStatus(), StationDict.ticketStatusName(ticket.getStatus()),
                ticket.getGrossWeight(), ticket.getTareWeight(), ticket.getNetWeight(), ticket.getTotalAmount(),
                ticket.getActiveVersionNo(), ticket.getEffectiveNetWeight(), ticket.getEffectiveTotalAmount(),
                ticket.isFrozenFlag(), ticket.getFrozenReason(), ticket.getFrozenReweighNo(),
                open != null, open == null ? null : open.getReweighNo(),
                open == null ? null : open.getStatus(),
                open == null ? null : StationDict.reweighStatusName(open.getStatus()),
                currentOwner, ticket.getLockVersion(), lineViews, versions);
    }
}
