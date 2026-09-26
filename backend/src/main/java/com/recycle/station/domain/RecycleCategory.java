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
 * 回收品类与回收价。
 * <p>
 * buyPrice = 当日回收单价（站里付给客户的钱，支出）
 * sellPrice = 当日出售单价（打包卖给下游再生资源厂的钱，收入）
 */
@Entity
@Table(name = "recycle_category")
public class RecycleCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_no", nullable = false, unique = true, length = 32)
    private String categoryNo;

    @Column(name = "category_name", nullable = false, length = 50)
    private String categoryName;

    /** 计量单位：KG 公斤 / SET 台 */
    @Column(name = "unit", nullable = false, length = 10)
    private String unit;

    @Column(name = "buy_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal buyPrice;

    @Column(name = "sell_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal sellPrice;

    /**
     * 是否收储（false 表示当天停收，只允许清零出库）。
     * 列名不能用 stored —— MySQL 8 里 STORED 是生成列的关键字。
     */
    @Column(name = "stock_flag", nullable = false)
    private Boolean stored;

    @Column(name = "remark", length = 100)
    private String remark;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 毛利额 */
    public BigDecimal marginAmount() {
        BigDecimal buy = buyPrice == null ? BigDecimal.ZERO : buyPrice;
        BigDecimal sell = sellPrice == null ? BigDecimal.ZERO : sellPrice;
        return sell.subtract(buy);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public BigDecimal getBuyPrice() {
        return buyPrice;
    }

    public void setBuyPrice(BigDecimal buyPrice) {
        this.buyPrice = buyPrice;
    }

    public BigDecimal getSellPrice() {
        return sellPrice;
    }

    public void setSellPrice(BigDecimal sellPrice) {
        this.sellPrice = sellPrice;
    }

    public Boolean getStored() {
        return stored;
    }

    public void setStored(Boolean stored) {
        this.stored = stored;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
