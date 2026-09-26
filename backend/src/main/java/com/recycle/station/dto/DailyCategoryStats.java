package com.recycle.station.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 日结对账 · 按「日期 + 品类」聚合的一行。
 * <p>
 * 它是 JPQL 构造器表达式的目标：{@code SELECT new com.recycle.station.dto.DailyCategoryStats(...)}
 * 配合 {@code GROUP BY} 直接落成对象，所以这里必须是普通类（带无参构造 + setter），不能是不可变 record。
 */
public class DailyCategoryStats {

    private LocalDate statDate;

    private String categoryNo;

    private String categoryName;

    private String unit;

    /** 回收量（按单位汇总：公斤或台） */
    private BigDecimal totalWeight;

    /** 回收支出 = Σ(重量 × 当日回收单价) */
    private BigDecimal buyAmount;

    /** 出售收入 = Σ(重量 × 当日出售单价) */
    private BigDecimal sellAmount;

    /** 毛利 = 出售收入 - 回收支出 */
    private BigDecimal grossProfit;

    /** 该日期该品类出现的明细行数 */
    private Long lineCount;

    public DailyCategoryStats() {
    }

    public DailyCategoryStats(LocalDate statDate, String categoryNo, String categoryName, String unit,
                              BigDecimal totalWeight, BigDecimal buyAmount, BigDecimal sellAmount,
                              BigDecimal grossProfit, Long lineCount) {
        this.statDate = statDate;
        this.categoryNo = categoryNo;
        this.categoryName = categoryName;
        this.unit = unit;
        this.totalWeight = totalWeight;
        this.buyAmount = buyAmount;
        this.sellAmount = sellAmount;
        this.grossProfit = grossProfit;
        this.lineCount = lineCount;
    }

    /** 毛利率（%）：分母为 0 时返回 0 */
    public BigDecimal profitRate() {
        if (sellAmount == null || sellAmount.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return grossProfit.multiply(BigDecimal.valueOf(100))
                .divide(sellAmount, 2, java.math.RoundingMode.HALF_UP);
    }

    public LocalDate getStatDate() {
        return statDate;
    }

    public void setStatDate(LocalDate statDate) {
        this.statDate = statDate;
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
