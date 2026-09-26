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
import com.recycle.station.service.VehicleService;

/**
 * 模块三：车辆与司机调度。
 */
@RestController
@RequestMapping("/api/vehicles")
@Validated
public class VehicleController {

    private final VehicleService vehicleService;

    public VehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    @GetMapping
    public ApiResponse<List<Views.VehicleView>> list() {
        return ApiResponse.success(vehicleService.list());
    }

    @PostMapping
    public ApiResponse<Views.VehicleView> create(@Validated @RequestBody Reqs.VehicleSaveReq req) {
        return ApiResponse.success("车辆已登记", vehicleService.create(req));
    }

    /** 状态切换：待命 / 在途 / 维修（在途回场时今日趟次 +1） */
    @PostMapping("/{id}/status")
    public ApiResponse<Views.VehicleView> changeStatus(@PathVariable Long id,
                                                       @Validated @RequestBody Reqs.VehicleStatusReq req) {
        return ApiResponse.success("调度已下发", vehicleService.changeStatus(id, req.status().trim()));
    }
}
