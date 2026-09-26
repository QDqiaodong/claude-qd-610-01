package com.recycle.station.controller;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.recycle.station.common.ApiResponse;
import com.recycle.station.dto.Reqs;
import com.recycle.station.dto.Views;
import com.recycle.station.service.ReweighService;

/**
 * 模块六：复称复核台。
 * <p>
 * 发起 → 补录 → 现场放行 / 复核（通过 / 驳回）→ 恢复结算，全链路在此收口。
 */
@RestController
@RequestMapping("/api/reweigh")
@Validated
public class ReweighController {

    private final ReweighService reweighService;

    public ReweighController(ReweighService reweighService) {
        this.reweighService = reweighService;
    }

    /** 复称台队列：未结束在前，已结束留痕在后 */
    @GetMapping("/orders")
    public ApiResponse<List<Views.ReweighView>> board() {
        return ApiResponse.success(reweighService.board());
    }

    @GetMapping("/orders/{reweighNo}")
    public ApiResponse<Views.ReweighView> detail(@PathVariable String reweighNo) {
        return ApiResponse.success(reweighService.detail(reweighNo));
    }

    /** 磅单维度：冻结原因、当前责任人、原始/有效口径、版本链 */
    @GetMapping("/ticket/{ticketNo}")
    public ApiResponse<Views.TicketReweighView> ticketView(@PathVariable String ticketNo) {
        return ApiResponse.success(reweighService.ticketReweighView(ticketNo));
    }

    /** 发起复称（立即冻结结算；幂等键随体携带，重复提交返回同一条） */
    @PostMapping("/initiate")
    public ApiResponse<Views.ReweighView> initiate(@Validated @RequestBody Reqs.ReweighInitiateReq req) {
        return ApiResponse.success("复称已发起，磅单进入结算冻结", reweighService.initiate(req));
    }

    /** 补录复称三项重量与明细重分配 */
    @PostMapping("/supplement")
    public ApiResponse<Views.ReweighView> supplement(@Validated @RequestBody Reqs.ReweighSupplementReq req) {
        return ApiResponse.success("复称数据已补录", reweighService.supplement(req));
    }

    /** 差异在双阈值内：另一名当班人员现场放行 */
    @PostMapping("/release")
    public ApiResponse<Views.ReweighView> release(@Validated @RequestBody Reqs.ReweighReleaseReq req) {
        return ApiResponse.success("现场放行完成，新重量版本已生效，磅单恢复结算", reweighService.release(req));
    }

    /** 阈值内但现场有争议：升级为待复核 */
    @PostMapping("/escalate")
    public ApiResponse<Views.ReweighView> escalate(@Validated @RequestBody Reqs.ReweighEscalateReq req) {
        return ApiResponse.success("已升级为待复核", reweighService.escalate(req));
    }

    /** 差异超阈值：复核通过 / 驳回 */
    @PostMapping("/review")
    public ApiResponse<Views.ReweighView> review(@Validated @RequestBody Reqs.ReweighReviewReq req) {
        String ok = "APPROVED".equalsIgnoreCase(req.result())
                ? "复核通过，新重量版本已生效，磅单恢复结算"
                : "复核已驳回，磅单沿用原重量与原金额并恢复结算";
        return ApiResponse.success(ok, reweighService.review(req));
    }
}
