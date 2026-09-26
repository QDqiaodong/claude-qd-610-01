package com.recycle.station.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 结算单：一张待结算的过磅单 → 一张结算单。
 * <p>
 * 这里刻意用 ticketNo 普通列而不是 {@code @ManyToOne}：结算单是财务凭证，
 * 过磅单作废时结算记录要留痕，不走级联删除。
 */
@Entity
@Table(name = "settlement")
public class Settlement {

    /** 结算单号前缀 */
    public static final String NO_PREFIX = "JS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settle_no", nullable = false, unique = true, length = 32)
    private String settleNo;

    @Column(name = "ticket_no", nullable = false, length = 32)
    private String ticketNo;

    @Column(name = "customer_name", nullable = false, length = 50)
    private String customerName;

    /** CASH 现金 / TRANSFER 转账 / DEDUCT 抵扣 */
    @Column(name = "pay_mode", nullable = false, length = 20)
    private String payMode;

    /** 应付金额（默认等于磅单总金额） */
    @Column(name = "payable_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal payableAmount;

    /** 实付金额（抹零或抵扣后真正付出去的钱） */
    @Column(name = "paid_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal paidAmount;

    /** 付款时采用的磅单金额版本号：明确"这张结算单付的是哪一版重量/金额"，复称放行后版本会变 */
    @Column(name = "amount_version", nullable = false)
    private Integer amountVersion = 1;

    @Column(name = "settle_time", nullable = false)
    private LocalDateTime settleTime;

    @Column(name = "operator", nullable = false, length = 30)
    private String operator;

    @Column(name = "remark", length = 100)
    private String remark;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSettleNo() {
        return settleNo;
    }

    public void setSettleNo(String settleNo) {
        this.settleNo = settleNo;
    }

    public String getTicketNo() {
        return ticketNo;
    }

    public void setTicketNo(String ticketNo) {
        this.ticketNo = ticketNo;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getPayMode() {
        return payMode;
    }

    public void setPayMode(String payMode) {
        this.payMode = payMode;
    }

    public BigDecimal getPayableAmount() {
        return payableAmount;
    }

    public void setPayableAmount(BigDecimal payableAmount) {
        this.payableAmount = payableAmount;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public void setPaidAmount(BigDecimal paidAmount) {
        this.paidAmount = paidAmount;
    }

    public Integer getAmountVersion() {
        return amountVersion;
    }

    public void setAmountVersion(Integer amountVersion) {
        this.amountVersion = amountVersion;
    }

    public LocalDateTime getSettleTime() {
        return settleTime;
    }

    public void setSettleTime(LocalDateTime settleTime) {
        this.settleTime = settleTime;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
