package com.recycle.station.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 过磅单明细行：一行一个品类（品类 + 重量 + 单价 + 小计）。
 * <p>
 * 复合主键（磅单号 + 行号）走 {@code @IdClass}：
 * 同一个 {@code ticket_no} 列既要作为主键列（可写），又要指向主单，
 * 所以主单那一侧的 {@code @JoinColumn} 标成 insertable=false / updatable=false，避免重复映射同一列。
 */
@Entity
@Table(name = "weigh_ticket_line")
@IdClass(WeighTicketLineId.class)
public class WeighTicketLine {

    @Id
    @Column(name = "ticket_no", nullable = false, length = 32)
    private String ticketNo;

    @Id
    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_no", referencedColumnName = "ticket_no", insertable = false, updatable = false)
    private WeighTicket ticket;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_no", referencedColumnName = "category_no", insertable = false, updatable = false)
    private RecycleCategory category;

    @Column(name = "category_no", nullable = false, length = 32)
    private String categoryNo;

    @Column(name = "category_name", nullable = false, length = 50)
    private String categoryName;

    /** 单位快照：调价或改品类时不影响历史磅单 */
    @Column(name = "unit", nullable = false, length = 10)
    private String unit;

    @Column(name = "weight", nullable = false, precision = 12, scale = 2)
    private BigDecimal weight;

    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "subtotal", nullable = false, precision = 14, scale = 2)
    private BigDecimal subtotal;

    public String getTicketNo() {
        return ticketNo;
    }

    public void setTicketNo(String ticketNo) {
        this.ticketNo = ticketNo;
    }

    public Integer getLineNo() {
        return lineNo;
    }

    public void setLineNo(Integer lineNo) {
        this.lineNo = lineNo;
    }

    public WeighTicket getTicket() {
        return ticket;
    }

    public void setTicket(WeighTicket ticket) {
        this.ticket = ticket;
    }

    public RecycleCategory getCategory() {
        return category;
    }

    public void setCategory(RecycleCategory category) {
        this.category = category;
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

    public BigDecimal getWeight() {
        return weight;
    }

    public void setWeight(BigDecimal weight) {
        this.weight = weight;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public void setSubtotal(BigDecimal subtotal) {
        this.subtotal = subtotal;
    }
}
