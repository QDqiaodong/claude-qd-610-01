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
import com.recycle.station.service.TicketService;

/**
 * 模块二：过磅单（含明细行）。
 */
@RestController
@RequestMapping("/api/tickets")
@Validated
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @GetMapping
    public ApiResponse<List<Views.TicketView>> list() {
        return ApiResponse.success(ticketService.list());
    }

    /** 待结算的磅单，结算页选单用 */
    @GetMapping("/pending")
    public ApiResponse<List<Views.TicketView>> pending() {
        return ApiResponse.success(ticketService.pending());
    }

    @GetMapping("/{ticketNo}")
    public ApiResponse<Views.TicketView> detail(@PathVariable String ticketNo) {
        return ApiResponse.success(ticketService.detail(ticketNo));
    }

    @PostMapping
    public ApiResponse<Views.TicketView> create(@Validated @RequestBody Reqs.TicketCreateReq req) {
        return ApiResponse.success("过磅单已生成", ticketService.create(req));
    }

    /** 状态流转：结算 / 作废 */
    @PostMapping("/{ticketNo}/status")
    public ApiResponse<Views.TicketView> changeStatus(@PathVariable String ticketNo,
                                                      @Validated @RequestBody Reqs.TicketStatusReq req) {
        return ApiResponse.success("磅单状态已更新", ticketService.changeStatus(ticketNo, req.status().trim()));
    }
}
