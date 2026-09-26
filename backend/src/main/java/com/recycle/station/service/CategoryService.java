package com.recycle.station.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.recycle.station.common.BizException;
import com.recycle.station.domain.RecycleCategory;
import com.recycle.station.domain.StationDict;
import com.recycle.station.dto.Reqs;
import com.recycle.station.dto.Views;
import com.recycle.station.repository.RecycleCategoryRepository;

/**
 * 模块一：回收品类与回收价（表格维护 + 行内调价）。
 */
@Service
public class CategoryService {

    private final RecycleCategoryRepository categoryRepository;

    public CategoryService(RecycleCategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    public List<Views.CategoryView> list() {
        List<Views.CategoryView> rows = new ArrayList<>();
        for (RecycleCategory c : categoryRepository.findAll()) {
            rows.add(toView(c));
        }
        return rows;
    }

    /** 开票页下拉框：只给还在收储的品类 */
    public List<Views.CategoryView> storedOptions() {
        List<Views.CategoryView> rows = new ArrayList<>();
        for (RecycleCategory c : categoryRepository.findByStoredTrueOrderByCategoryNoAsc()) {
            rows.add(toView(c));
        }
        return rows;
    }

    @Transactional
    public Views.CategoryView create(Reqs.CategorySaveReq req) {
        checkPrice(req.unit(), req.buyPrice(), req.sellPrice());
        if (categoryRepository.findByCategoryNo(req.categoryNo()) != null) {
            throw new BizException("品类编号 " + req.categoryNo() + " 已存在");
        }
        RecycleCategory category = new RecycleCategory();
        category.setCategoryNo(req.categoryNo().trim());
        category.setCategoryName(req.categoryName().trim());
        category.setUnit(req.unit());
        category.setBuyPrice(scaled(req.buyPrice()));
        category.setSellPrice(scaled(req.sellPrice()));
        category.setStored(req.stored() == null || req.stored());
        category.setRemark(req.remark());
        category.setUpdatedAt(LocalDateTime.now());
        return toView(categoryRepository.save(category));
    }

    /** 行内调价：只动当天牌价，历史磅单上的单价快照不变 */
    @Transactional
    public Views.CategoryView adjustPrice(Long id, Reqs.PriceAdjustReq req) {
        RecycleCategory category = categoryRepository.findById(id)
                .orElseThrow(() -> new BizException("品类不存在"));
        if (req.reason() == null || req.reason().trim().length() < 2) {
            throw new BizException("调价必须填写理由（不少于 2 个字），便于日结对账追溯");
        }
        checkPrice(category.getUnit(), req.buyPrice(), req.sellPrice());
        category.setBuyPrice(scaled(req.buyPrice()));
        category.setSellPrice(scaled(req.sellPrice()));
        category.setUpdatedAt(LocalDateTime.now());
        return toView(categoryRepository.save(category));
    }

    @Transactional
    public Views.CategoryView toggleStored(Long id) {
        RecycleCategory category = categoryRepository.findById(id)
                .orElseThrow(() -> new BizException("品类不存在"));
        category.setStored(!Boolean.TRUE.equals(category.getStored()));
        category.setUpdatedAt(LocalDateTime.now());
        return toView(categoryRepository.save(category));
    }

    /** 单价合法性：单位合法、非负、不超上限、且不能卖得比收得便宜（倒挂直接拦） */
    private void checkPrice(String unit, BigDecimal buyPrice, BigDecimal sellPrice) {
        if (!StationDict.isValidUnit(unit)) {
            throw new BizException("计量单位只能是 KG（公斤）或 SET（台）");
        }
        if (buyPrice == null || sellPrice == null) {
            throw new BizException("回收单价与出售单价都必须填写");
        }
        if (buyPrice.compareTo(BigDecimal.ZERO) < 0 || sellPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("单价不能为负数");
        }
        if (buyPrice.compareTo(StationDict.MAX_UNIT_PRICE) > 0 || sellPrice.compareTo(StationDict.MAX_UNIT_PRICE) > 0) {
            throw new BizException("单价超过上限 " + StationDict.MAX_UNIT_PRICE + " 元，请确认是否多敲了一个 0");
        }
        if (sellPrice.compareTo(buyPrice) < 0) {
            throw new BizException("出售单价不能低于回收单价，否则这一品类当天就是倒挂亏损，不允许挂出这个牌价");
        }
    }

    private BigDecimal scaled(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    public static Views.CategoryView toView(RecycleCategory c) {
        return new Views.CategoryView(
                c.getId(),
                c.getCategoryNo(),
                c.getCategoryName(),
                c.getUnit(),
                StationDict.unitName(c.getUnit()),
                c.getBuyPrice(),
                c.getSellPrice(),
                c.marginAmount(),
                Boolean.TRUE.equals(c.getStored()),
                c.getRemark(),
                c.getUpdatedAt());
    }

    /** 给报表页用的品类号 → 名称映射 */
    public Map<String, String> nameMap() {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        for (RecycleCategory c : categoryRepository.findAll()) {
            map.put(c.getCategoryNo(), c.getCategoryName());
        }
        return map;
    }
}
