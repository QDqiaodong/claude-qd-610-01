package com.recycle.station.controller;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.recycle.station.common.ApiResponse;
import com.recycle.station.dto.Reqs;
import com.recycle.station.dto.Views;
import com.recycle.station.service.SettlementService;

/**
 * 模块四：结算单。
 */
@RestController
@RequestMapping("/api/settlements")
@Validated
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @GetMapping
    public ApiResponse<List<Views.SettlementView>> list() {
        return ApiResponse.success(settlementService.list());
    }

    /** 给一张待结算磅单打款 */
    @PostMapping
    public ApiResponse<Views.SettlementView> settle(@Validated @RequestBody Reqs.SettleReq req) {
        return ApiResponse.success("结算完成，磅单转为已结算", settlementService.settle(req));
    }
}
