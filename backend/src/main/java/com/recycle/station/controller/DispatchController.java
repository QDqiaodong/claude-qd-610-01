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

/**
 * 模块七：派车联锁。车辆 待命→在途 必须派车确认（司机 + 计划回场 + 证照覆盖 +
 * 资格快照），回场结束出车。
 */
@RestController
@RequestMapping("/api/dispatch")
@Validated
public class DispatchController {

    private final DispatchService dispatchService;

    public DispatchController(DispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    /** 车辆当前可派状态（含逐条不可派原因） */
    @GetMapping("/board")
    public ApiResponse<List<Views.VehicleDispatchView>> board() {
        return ApiResponse.success(dispatchService.board());
    }

    /** 派车试算：返回联锁结论与将固化的快照，不改变任何状态 */
    @PostMapping("/check")
    public ApiResponse<Views.DispatchCheckView> check(@Validated @RequestBody Reqs.DispatchCheckReq req) {
        return ApiResponse.success(dispatchService.check(req));
    }

    /** 派车确认：核验证照有效期覆盖计划回场，固化派车资格快照 */
    @PostMapping("/trips")
    public ApiResponse<Views.TripView> dispatch(@Validated @RequestBody Reqs.DispatchCreateReq req) {
        return ApiResponse.success("派车成功，派车资格快照已固化", dispatchService.dispatch(req));
    }

    /** 在途回场 */
    @PostMapping("/trips/{id}/return")
    public ApiResponse<Views.TripView> doReturn(@PathVariable Long id,
                                                @Validated @RequestBody Reqs.DispatchReturnReq req) {
        return ApiResponse.success("已登记回场", dispatchService.doReturn(id, req));
    }

    /** 单次出车的资格快照 */
    @GetMapping("/trips/{id}")
    public ApiResponse<Views.TripView> trip(@PathVariable Long id) {
        return ApiResponse.success(dispatchService.tripDetail(id));
    }

    @GetMapping("/trips")
    public ApiResponse<List<Views.TripView>> recent() {
        return ApiResponse.success(dispatchService.recentTrips());
    }

    @GetMapping("/on-road")
    public ApiResponse<List<Views.TripView>> onRoad() {
        return ApiResponse.success(dispatchService.onRoadTrips());
    }
}
