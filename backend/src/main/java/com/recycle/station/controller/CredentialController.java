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
import com.recycle.station.service.CredentialService;

/**
 * 模块七：证照有效期联锁台（司机证照 / 车辆证照分开管理）。
 * 草稿 → 待复核 → 生效 →（驳回 / 撤销 / 到期 / 被新版本替代），旧版本永久保留。
 */
@RestController
@RequestMapping("/api/credentials")
@Validated
public class CredentialController {

    private final CredentialService credentialService;

    public CredentialController(CredentialService credentialService) {
        this.credentialService = credentialService;
    }

    // ---------------- 司机花名册 ----------------

    @GetMapping("/drivers")
    public ApiResponse<List<Views.DriverView>> drivers() {
        return ApiResponse.success(credentialService.listDrivers());
    }

    @PostMapping("/drivers")
    public ApiResponse<Views.DriverView> createDriver(@Validated @RequestBody Reqs.DriverSaveReq req) {
        return ApiResponse.success("司机已登记", credentialService.createDriver(req));
    }

    @PostMapping("/drivers/{id}")
    public ApiResponse<Views.DriverView> updateDriver(@PathVariable Long id,
                                                      @Validated @RequestBody Reqs.DriverSaveReq req) {
        return ApiResponse.success("司机资料已更新", credentialService.updateDriver(id, req));
    }

    // ---------------- 证照列表 / 队列 / 版本链 ----------------

    /** 证照列表：可按 ownerType+ownerId 过滤；不传则返回全部版本 */
    @GetMapping("/docs")
    public ApiResponse<List<Views.CredentialDocView>> docs(@RequestParam(required = false) String ownerType,
                                                           @RequestParam(required = false) Long ownerId) {
        return ApiResponse.success(credentialService.listDocs(ownerType, ownerId));
    }

    @GetMapping("/review-queue")
    public ApiResponse<List<Views.ReviewQueueItem>> reviewQueue() {
        return ApiResponse.success(credentialService.reviewQueue());
    }

    @GetMapping("/groups/{groupUuid}")
    public ApiResponse<Views.CredentialGroupView> group(@PathVariable String groupUuid) {
        return ApiResponse.success(credentialService.groupDetail(groupUuid));
    }

    /** 正在使用某份资料的在途车辆（复核台撤销/换版前看影响面） */
    @GetMapping("/docs/{id}/affected-trips")
    public ApiResponse<List<Views.AffectedTripView>> affected(@PathVariable Long id) {
        return ApiResponse.success(credentialService.listAffectedOnRoad(id));
    }

    // ---------------- 状态流转 ----------------

    @PostMapping("/docs")
    public ApiResponse<Views.CredentialDocView> saveDraft(@Validated @RequestBody Reqs.CredentialSaveReq req) {
        return ApiResponse.success("草稿已保存", credentialService.saveDraft(req));
    }

    @PostMapping("/docs/{id}")
    public ApiResponse<Views.CredentialDocView> updateDraft(@PathVariable Long id,
                                                            @Validated @RequestBody Reqs.CredentialUpdateReq req) {
        return ApiResponse.success("草稿已更新", credentialService.updateDraft(id, req));
    }

    @PostMapping("/docs/{id}/submit")
    public ApiResponse<Views.CredentialDocView> submit(@PathVariable Long id,
                                                       @Validated @RequestBody Reqs.CredentialSubmitReq req) {
        return ApiResponse.success("已提交复核", credentialService.submit(id, req));
    }

    @PostMapping("/docs/{id}/approve")
    public ApiResponse<Views.CredentialDocView> approve(@PathVariable Long id,
                                                        @Validated @RequestBody Reqs.CredentialApproveReq req) {
        return ApiResponse.success("复核通过，资料已生效", credentialService.approve(id, req));
    }

    @PostMapping("/docs/{id}/reject")
    public ApiResponse<Views.CredentialDocView> reject(@PathVariable Long id,
                                                       @Validated @RequestBody Reqs.CredentialRejectReq req) {
        return ApiResponse.success("已驳回", credentialService.reject(id, req));
    }

    @PostMapping("/docs/{id}/revoke")
    public ApiResponse<List<Views.AffectedTripView>> revoke(@PathVariable Long id,
                                                            @Validated @RequestBody Reqs.CredentialRevokeReq req) {
        List<Views.AffectedTripView> affected = credentialService.revoke(id, req);
        return ApiResponse.success("已撤销，" + affected.size() + " 辆在途车已标记待调度处置", affected);
    }
}
