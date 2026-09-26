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
import com.recycle.station.service.CategoryService;

/**
 * 模块一：回收品类与回收价。
 */
@RestController
@RequestMapping("/api/categories")
@Validated
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /** 全部品类（含停收的） */
    @GetMapping
    public ApiResponse<List<Views.CategoryView>> list() {
        return ApiResponse.success(categoryService.list());
    }

    /** 开票 / 明细行下拉框：只给还在收储的品类 */
    @GetMapping("/options")
    public ApiResponse<List<Views.CategoryView>> options() {
        return ApiResponse.success(categoryService.storedOptions());
    }

    @PostMapping
    public ApiResponse<Views.CategoryView> create(@Validated @RequestBody Reqs.CategorySaveReq req) {
        return ApiResponse.success("品类已建档", categoryService.create(req));
    }

    /** 行内调价 */
    @PostMapping("/{id}/price")
    public ApiResponse<Views.CategoryView> adjustPrice(@PathVariable Long id,
                                                       @Validated @RequestBody Reqs.PriceAdjustReq req) {
        return ApiResponse.success("牌价已更新", categoryService.adjustPrice(id, req));
    }

    /** 收储开关：当天停收的品类不能开票 */
    @PostMapping("/{id}/stored")
    public ApiResponse<Views.CategoryView> toggleStored(@PathVariable Long id) {
        return ApiResponse.success("收储状态已切换", categoryService.toggleStored(id));
    }
}
