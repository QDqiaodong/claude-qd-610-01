package com.recycle.station.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 日结对账 · 按「日期」聚合的一行（一整天所有品类合起来）。同样是 JPQL 构造器表达式的目标类。
 */
public class DailySummaryRow {

    private LocalDate statDate;

    private BigDecimal totalWeight;

    private BigDecimal buyAmount;

    private BigDecimal sellAmount;

    private BigDecimal grossProfit;

    /** 当天过磅单张数 */
    private Long ticketCount;

    public DailySummaryRow() {
    }

    public DailySummaryRow(LocalDate statDate, BigDecimal totalWeight, BigDecimal buyAmount,
                           BigDecimal sellAmount, BigDecimal grossProfit, Long ticketCount) {
        this.statDate = statDate;
        this.totalWeight = totalWeight;
        this.buyAmount = buyAmount;
        this.sellAmount = sellAmount;
        this.grossProfit = grossProfit;
        this.ticketCount = ticketCount;
    }

    /** 磅单级聚合用：出售收入 / 毛利由品类级聚合后在服务层补齐 */
    public DailySummaryRow(LocalDate statDate, BigDecimal totalWeight, BigDecimal buyAmount, Long ticketCount) {
        this(statDate, totalWeight, buyAmount, BigDecimal.ZERO, BigDecimal.ZERO, ticketCount);
    }

    public LocalDate getStatDate() {
        return statDate;
    }

    public void setStatDate(LocalDate statDate) {
        this.statDate = statDate;
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

    public Long getTicketCount() {
        return ticketCount;
    }

    public void setTicketCount(Long ticketCount) {
        this.ticketCount = ticketCount;
    }
}
