package com.recycle.station.controller;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.recycle.station.common.ApiResponse;
import com.recycle.station.dto.Reqs;
import com.recycle.station.dto.Views;
import com.recycle.station.service.CertService;
import com.recycle.station.service.DriverService;
import com.recycle.station.service.DispatchService;

/**
 * 模块七：证照有效期联锁台。
 * 分别管理司机证照与车辆证照的版本链、待复核队列、复核生效 / 驳回 / 撤销，
 * 并提供派车资格预核对、出车单与派车资格快照查询。
 */
@RestController
@RequestMapping("/api/certs")
@Validated
public class CertController {

    private final CertService certService;
    private final DriverService driverService;
    private final DispatchService dispatchService;

    public CertController(CertService certService, DriverService driverService,
                          DispatchService dispatchService) {
        this.certService = certService;
        this.driverService = driverService;
        this.dispatchService = dispatchService;
    }

    // ---------------- 司机名册 ----------------

    @GetMapping("/drivers")
    public ApiResponse<List<Views.DriverView>> drivers(@RequestParam(required = false) String status) {
        return ApiResponse.success(driverService.list(status));
    }

    @PostMapping("/drivers")
    public ApiResponse<Views.DriverView> createDriver(@Validated @RequestBody Reqs.DriverSaveReq req) {
        return ApiResponse.success("司机已建档", driverService.create(req));
    }

    // ---------------- 证照列表 / 版本链 ----------------

    /** 证照列表：subjectType=DRIVER 司机证照 / VEHICLE 车辆证照，不传全部（打开列表即做到期扫描） */
    @GetMapping("/series")
    public ApiResponse<List<Views.CertSeriesView>> series(@RequestParam(required = false) String subjectType) {
        return ApiResponse.success(certService.listSeries(subjectType));
    }

    @GetMapping("/series/{id}")
    public ApiResponse<Views.CertSeriesView> seriesDetail(@PathVariable Long id) {
        return ApiResponse.success(certService.seriesDetail(id));
    }

    /** 待复核队列：currentReviewer 用于标注"自己经办、不可自己复核"的记录 */
    @GetMapping("/review-queue")
    public ApiResponse<List<Views.CertReviewQueueItem>> reviewQueue(
            @RequestParam(required = false) String currentReviewer) {
        return ApiResponse.success(certService.reviewQueue(currentReviewer));
    }

    // ---------------- 录入 / 提交 / 复核 / 撤销 ----------------

    @PostMapping("/documents")
    public ApiResponse<Views.CertSeriesView> saveDraft(@Validated @RequestBody Reqs.CertDraftReq req) {
        boolean submit = Boolean.TRUE.equals(req.submit());
        return ApiResponse.success(submit ? "资料已提交复核" : "草稿已保存", certService.saveDraft(req));
    }

    @PostMapping("/documents/submit")
    public ApiResponse<Views.CertSeriesView> submit(@Validated @RequestBody Reqs.CertSubmitReq req) {
        return ApiResponse.success("资料已提交复核", certService.submit(req));
    }

    @PostMapping("/documents/approve")
    public ApiResponse<Views.CertSeriesView> approve(@Validated @RequestBody Reqs.CertApproveReq req) {
        return ApiResponse.success("资料已复核生效", certService.approve(req));
    }

    @PostMapping("/documents/reject")
    public ApiResponse<Views.CertSeriesView> reject(@Validated @RequestBody Reqs.CertRejectReq req) {
        return ApiResponse.success("资料已驳回", certService.reject(req));
    }

    @PostMapping("/documents/revoke")
    public ApiResponse<Views.CertSeriesView> revoke(@Validated @RequestBody Reqs.CertRevokeReq req) {
        return ApiResponse.success("生效资料已撤销，使用它的在途车辆已标记待调度处置", certService.revoke(req));
    }

    // ---------------- 派车资格预核对 / 出车快照 ----------------

    /** 派车预核对（不落数据）：改选司机或改计划回场时间时实时返回可派结论与将固化的快照 */
    @PostMapping("/dispatch-check")
    public ApiResponse<Views.DispatchCheckView> dispatchCheck(@Validated @RequestBody Reqs.DispatchCheckReq req) {
        return ApiResponse.success(certService.precheck(req));
    }

    /** 出车单列表（status: OPEN / PENDING_DISPOSAL / FINISHED，不传全部） */
    @GetMapping("/trips")
    public ApiResponse<List<Views.DispatchTripView>> trips(@RequestParam(required = false) String status) {
        return ApiResponse.success(dispatchService.list(status));
    }

    @GetMapping("/trips/{id}")
    public ApiResponse<Views.DispatchTripView> tripDetail(@PathVariable Long id) {
        return ApiResponse.success(dispatchService.detail(id));
    }

    @GetMapping("/vehicles/{id}/trips")
    public ApiResponse<List<Views.DispatchTripView>> vehicleTrips(@PathVariable Long id) {
        return ApiResponse.success(dispatchService.vehicleTrips(id));
    }

    /** 待调度处置：登记处置说明 */
    @PostMapping("/trips/{id}/disposition")
    public ApiResponse<Views.DispatchTripView> disposition(@PathVariable Long id,
                                                           @Validated @RequestBody Reqs.DispositionReq req) {
        return ApiResponse.success("处置已登记", dispatchService.dispose(req));
    }
}
