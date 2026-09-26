package com.recycle.station.dto;

import java.math.BigDecimal;

/**
 * 日结对账 · 跨日期按「品类」汇总的一行（给横条图排序用）。
 * JPQL 构造器表达式的目标类。
 */
public class CategoryTotalRow {

    private String categoryNo;

    private String categoryName;

    private String unit;

    private BigDecimal totalWeight;

    private BigDecimal buyAmount;

    private BigDecimal sellAmount;

    private BigDecimal grossProfit;

    private Long lineCount;

    public CategoryTotalRow() {
    }

    public CategoryTotalRow(String categoryNo, String categoryName, String unit, BigDecimal totalWeight,
                            BigDecimal buyAmount, BigDecimal sellAmount, BigDecimal grossProfit, Long lineCount) {
        this.categoryNo = categoryNo;
        this.categoryName = categoryName;
        this.unit = unit;
        this.totalWeight = totalWeight;
        this.buyAmount = buyAmount;
        this.sellAmount = sellAmount;
        this.grossProfit = grossProfit;
        this.lineCount = lineCount;
    }

    /** 毛利率（%） */
    public BigDecimal profitRate() {
        if (sellAmount == null || sellAmount.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return grossProfit.multiply(BigDecimal.valueOf(100))
                .divide(sellAmount, 2, java.math.RoundingMode.HALF_UP);
    }

    public String getCategoryNo() {
        return categoryNo;
    }

    public void setCategoryNo(String categoryNo) {
        this.categoryNo = categoryNo;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public BigDecimal getTotalWeight() {
        return totalWeight;
    }

    public void setTotalWeight(BigDecimal totalWeight) {
        this.totalWeight = totalWeight;
    }

    public BigDecimal getBuyAmount() {
        return buyAmount;
    }

    public void setBuyAmount(BigDecimal buyAmount) {
        this.buyAmount = buyAmount;
    }

    public BigDecimal getSellAmount() {
        return sellAmount;
    }

    public void setSellAmount(BigDecimal sellAmount) {
        this.sellAmount = sellAmount;
    }

    public BigDecimal getGrossProfit() {
        return grossProfit;
    }

    public void setGrossProfit(BigDecimal grossProfit) {
        this.grossProfit = grossProfit;
    }

    public Long getLineCount() {
        return lineCount;
    }

    public void setLineCount(Long lineCount) {
        this.lineCount = lineCount;
    }
}
