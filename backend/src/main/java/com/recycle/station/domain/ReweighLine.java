package com.recycle.station.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 复称单明细行：品类 / 单位 / 单价全部沿用原单快照（发起时从 weigh_ticket_line 原样拷贝），
 * 只有 {@link #newWeight} 允许在补录时重新分配；
 * {@link #subtotal} = newWeight × {@link #unitPrice}，绝不按后来调过的牌价重算。
 */
@Entity
@Table(name = "reweigh_line")
public class ReweighLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reweigh_id", referencedColumnName = "id")
    private ReweighOrder reweigh;

    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    @Column(name = "category_no", nullable = false, length = 32)
    private String categoryNo;

    @Column(name = "category_name", nullable = false, length = 50)
    private String categoryName;

    @Column(name = "unit", nullable = false, length = 10)
    private String unit;

    @Column(name = "origin_weight", nullable = false, precision = 12, scale = 2)
    private BigDecimal originWeight;

    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "new_weight", precision = 12, scale = 2)
    private BigDecimal newWeight;

    @Column(name = "subtotal", precision = 14, scale = 2)
    private BigDecimal subtotal;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ReweighOrder getReweigh() {
        return reweigh;
    }

    public void setReweigh(ReweighOrder reweigh) {
        this.reweigh = reweigh;
    }

    public Integer getLineNo() {
        return lineNo;
    }

    public void setLineNo(Integer lineNo) {
        this.lineNo = lineNo;
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

    public BigDecimal getOriginWeight() {
        return originWeight;
    }

    public void setOriginWeight(BigDecimal originWeight) {
        this.originWeight = originWeight;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public BigDecimal getNewWeight() {
        return newWeight;
    }

    public void setNewWeight(BigDecimal newWeight) {
        this.newWeight = newWeight;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public void setSubtotal(BigDecimal subtotal) {
        this.subtotal = subtotal;
    }
}
