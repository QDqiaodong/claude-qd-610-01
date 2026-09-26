package com.recycle.station.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * 磅单重量版本：V1 是建档版本（重量与金额仍以 weigh_ticket 原始列为准，不在这里复制），
 * 每放行/通过一次复称就新增一行 V2、V3…… 版本链只追加不改写，
 * 磅单用 active_version_no 指向当前有效版本。
 */
@Entity
@Table(name = "weigh_ticket_version")
public class WeighTicketVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_no", nullable = false, length = 32)
    private String ticketNo;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    /** 本版本由哪张复称单产生（V1 为空） */
    @Column(name = "source_reweigh_no", length = 32)
    private String sourceReweighNo;

    @Column(name = "gross_weight", nullable = false, precision = 12, scale = 2)
    private BigDecimal grossWeight;

    @Column(name = "tare_weight", nullable = false, precision = 12, scale = 2)
    private BigDecimal tareWeight;

    @Column(name = "net_weight", nullable = false, precision = 12, scale = 2)
    private BigDecimal netWeight;

    @Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "created_by", nullable = false, length = 30)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "version", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("lineNo ASC")
    private List<WeighTicketVersionLine> lines = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTicketNo() {
        return ticketNo;
    }

    public void setTicketNo(String ticketNo) {
        this.ticketNo = ticketNo;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getSourceReweighNo() {
        return sourceReweighNo;
    }

    public void setSourceReweighNo(String sourceReweighNo) {
        this.sourceReweighNo = sourceReweighNo;
    }

    public BigDecimal getGrossWeight() {
        return grossWeight;
    }

    public void setGrossWeight(BigDecimal grossWeight) {
        this.grossWeight = grossWeight;
    }

    public BigDecimal getTareWeight() {
        return tareWeight;
    }

    public void setTareWeight(BigDecimal tareWeight) {
        this.tareWeight = tareWeight;
    }

    public BigDecimal getNetWeight() {
        return netWeight;
    }

    public void setNetWeight(BigDecimal netWeight) {
        this.netWeight = netWeight;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public List<WeighTicketVersionLine> getLines() {
        return lines;
    }

    public void setLines(List<WeighTicketVersionLine> lines) {
        this.lines = lines;
    }
}
