package com.recycle.station.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.recycle.station.common.ApiResponse;
import com.recycle.station.dto.CategoryTotalRow;
import com.recycle.station.dto.CustomerRankRow;
import com.recycle.station.dto.DailyCategoryStats;
import com.recycle.station.dto.DailySummaryRow;
import com.recycle.station.dto.Views;
import com.recycle.station.service.StatsService;

/**
 * 模块五：日结对账报表（JPQL 聚合 + 构造器表达式）。
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /** 首页指标卡片 */
    @GetMapping("/overview")
    public ApiResponse<Views.OverviewView> overview() {
        return ApiResponse.success(statsService.overview());
    }

    /** 按日期 + 品类聚合 */
    @GetMapping("/daily-category")
    public ApiResponse<List<DailyCategoryStats>> dailyCategory(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.success(statsService.dailyCategory(defaultFrom(from), defaultTo(to)));
    }

    /** 按日期汇总 */
    @GetMapping("/daily-summary")
    public ApiResponse<List<DailySummaryRow>> dailySummary(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.success(statsService.dailySummary(defaultFrom(from), defaultTo(to)));
    }

    /** 品类汇总（横条图数据源） */
    @GetMapping("/category-total")
    public ApiResponse<List<CategoryTotalRow>> categoryTotal(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.success(statsService.categoryTotal(defaultFrom(from), defaultTo(to)));
    }

    /** 客户供货 TOP 榜 */
    @GetMapping("/customer-top")
    public ApiResponse<List<CustomerRankRow>> customerTop(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(statsService.customerTop(defaultFrom(from), defaultTo(to), limit));
    }

    /** 报表页一屏打包：明细 + 日期汇总 + 品类 + 客户 TOP */
    @GetMapping("/board")
    public ApiResponse<Map<String, Object>> board(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(statsService.board(defaultFrom(from), defaultTo(to), limit));
    }

    /** 默认查最近 7 天（含今天） */
    private String defaultFrom(String from) {
        if (from != null && !from.isBlank()) {
            return from.trim();
        }
        return LocalDate.now().minusDays(6).toString();
    }

    private String defaultTo(String to) {
        if (to != null && !to.isBlank()) {
            return to.trim();
        }
        return LocalDate.now().toString();
    }
}
