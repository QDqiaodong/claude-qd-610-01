package com.recycle.station.dto;

import java.math.BigDecimal;

/**
 * 客户供货 TOP 榜的一行：JPQL 聚合 + 构造器表达式直接映射。
 */
public class CustomerRankRow {

    private String customerName;

    private String customerType;

    private Long ticketCount;

    private BigDecimal totalWeight;

    private BigDecimal totalAmount;

    public CustomerRankRow() {
    }

    public CustomerRankRow(String customerName, String customerType, Long ticketCount,
                           BigDecimal totalWeight, BigDecimal totalAmount) {
        this.customerName = customerName;
        this.customerType = customerType;
        this.ticketCount = ticketCount;
        this.totalWeight = totalWeight;
        this.totalAmount = totalAmount;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerType() {
        return customerType;
    }

    public void setCustomerType(String customerType) {
        this.customerType = customerType;
    }

    public Long getTicketCount() {
        return ticketCount;
    }

    public void setTicketCount(Long ticketCount) {
        this.ticketCount = ticketCount;
    }

    public BigDecimal getTotalWeight() {
        return totalWeight;
    }

    public void setTotalWeight(BigDecimal totalWeight) {
        this.totalWeight = totalWeight;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }
}
