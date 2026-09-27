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
import com.recycle.station.service.DispatchService;
import com.recycle.station.service.VehicleService;

/**
 * 模块三 + 模块七：车辆与司机调度。
 * 待命→在途不再是简单改状态：必须派车（选司机 + 计划回场），核对证照并固化派车资格快照。
 */
@RestController
@RequestMapping("/api/vehicles")
@Validated
public class VehicleController {

    private final VehicleService vehicleService;
    private final DispatchService dispatchService;

    public VehicleController(VehicleService vehicleService, DispatchService dispatchService) {
        this.vehicleService = vehicleService;
        this.dispatchService = dispatchService;
    }

    @GetMapping
    public ApiResponse<List<Views.VehicleView>> list() {
        return ApiResponse.success(vehicleService.list());
    }

    @PostMapping
    public ApiResponse<Views.VehicleView> create(@Validated @RequestBody Reqs.VehicleSaveReq req) {
        return ApiResponse.success("车辆已登记", vehicleService.create(req));
    }

    /** 状态切换：仅允许 待命⇄维修；在途的两个方向必须走派车 / 回场 */
    @PostMapping("/{id}/status")
    public ApiResponse<Views.VehicleView> changeStatus(@PathVariable Long id,
                                                       @Validated @RequestBody Reqs.VehicleStatusReq req) {
        return ApiResponse.success("调度已下发", vehicleService.changeStatus(id, req.status().trim()));
    }

    /** 派车确认：待命 → 在途，选定本次司机与计划回场时间，固化派车资格快照 */
    @PostMapping("/dispatch")
    public ApiResponse<Views.DispatchTripView> dispatch(@Validated @RequestBody Reqs.DispatchReq req) {
        return ApiResponse.success("派车已确认，本次证照版本已固化为派车资格快照", dispatchService.dispatch(req));
    }

    /** 在途回场：在途 / 待调度处置 → 待命，今日趟次 +1 */
    @PostMapping("/trips/return")
    public ApiResponse<Views.DispatchTripView> finishTrip(@Validated @RequestBody Reqs.ReturnReq req) {
        return ApiResponse.success("已确认回场", dispatchService.finishTrip(req));
    }
}
