package com.recycle.station.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 过磅单（一次进场：毛重 - 皮重 = 净重，净重按品类拆进明细行）。
 * <p>
 * 注意 {@code status} 故意不写默认值：实体字段上的默认值会被 Jackson 当成"用户传了"，
 * 局部更新时把别的字段刷回默认值。
 * <p>
 * 复称闭环（模块六）：{@link #grossWeight}/{@link #tareWeight}/{@link #netWeight}/
 * {@link #totalAmount} 是建档原始值，<b>任何情况下都不允许覆盖</b>；
 * 复称放行后结算与报表改走 {@link #effectiveNetWeight}/{@link #effectiveTotalAmount}
 * 与 {@link #activeVersionNo} 指向的版本。复称处理期间 {@link #frozenFlag}=1，结算台无法付款。
 */
@Entity
@Table(name = "weigh_ticket")
public class WeighTicket {

    /** 过磅单号前缀 */
    public static final String NO_PREFIX = "WB";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_no", nullable = false, unique = true, length = 32)
    private String ticketNo;

    @Column(name = "customer_name", nullable = false, length = 50)
    private String customerName;

    /** PERSONAL 个人 / MERCHANT 商户 */
    @Column(name = "customer_type", nullable = false, length = 20)
    private String customerType;

    @Column(name = "weigh_time", nullable = false)
    private LocalDateTime weighTime;

    /** 归属统计日期（报表按它分组，不一定等于过磅时间的自然日，跨零点班次会人工归日） */
    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @Column(name = "plate_no", length = 20)
    private String plateNo;

    @Column(name = "gross_weight", nullable = false, precision = 12, scale = 2)
    private BigDecimal grossWeight;

    @Column(name = "tare_weight", nullable = false, precision = 12, scale = 2)
    private BigDecimal tareWeight;

    @Column(name = "net_weight", nullable = false, precision = 12, scale = 2)
    private BigDecimal netWeight;

    @Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;

    /** PENDING 待结算 / SETTLED 已结算 / VOID 作废 */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "remark", length = 100)
    private String remark;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // ---------------- 复称闭环字段（模块六） ----------------

    /** 结算冻结标记：复称从未结束到终审期间为 true，付款台一律拦下 */
    @Column(name = "frozen_flag", nullable = false)
    private boolean frozenFlag = false;

    /** 冻结原因（人话，直接展示给过磅人员与付款人员） */
    @Column(name = "frozen_reason", length = 200)
    private String frozenReason;

    /** 造成冻结的复称单号 */
    @Column(name = "frozen_reweigh_no", length = 32)
    private String frozenReweighNo;

    /** 当前有效重量版本号：1=原建档版本；复称放行/通过后指向新版本 */
    @Column(name = "active_version_no", nullable = false)
    private Integer activeVersionNo = 1;

    /** 当前有效净重（结算口径），建档时=净重，复称生效后=复称净重 */
    @Column(name = "effective_net_weight", nullable = false, precision = 12, scale = 2)
    private BigDecimal effectiveNetWeight;

    /** 当前有效应付金额（结算口径），按原单单价快照计算，绝不套后来调过的牌价 */
    @Column(name = "effective_total_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal effectiveTotalAmount;

    /** JPA 乐观锁：旧页面拿着过期数据提交结算 / 作废时直接撞 VERSION_CONFLICT */
    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    /** 明细行随主单一并保存 / 删除 */
    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WeighTicketLine> lines = new ArrayList<>();

    /** 清空明细并保持一致的两端关系 */
    public void clearLines() {
        if (lines != null) {
            lines.clear();
        }
    }

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

    public LocalDateTime getWeighTime() {
        return weighTime;
    }

    public void setWeighTime(LocalDateTime weighTime) {
        this.weighTime = weighTime;
    }

    public LocalDate getStatDate() {
        return statDate;
    }

    public void setStatDate(LocalDate statDate) {
        this.statDate = statDate;
    }

    public String getPlateNo() {
        return plateNo;
    }

    public void setPlateNo(String plateNo) {
        this.plateNo = plateNo;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public List<WeighTicketLine> getLines() {
        return lines;
    }

    public void setLines(List<WeighTicketLine> lines) {
        this.lines = lines;
    }

    public boolean isFrozenFlag() {
        return frozenFlag;
    }

    public void setFrozenFlag(boolean frozenFlag) {
        this.frozenFlag = frozenFlag;
    }

    public String getFrozenReason() {
        return frozenReason;
    }

    public void setFrozenReason(String frozenReason) {
        this.frozenReason = frozenReason;
    }

    public String getFrozenReweighNo() {
        return frozenReweighNo;
    }

    public void setFrozenReweighNo(String frozenReweighNo) {
        this.frozenReweighNo = frozenReweighNo;
    }

    public Integer getActiveVersionNo() {
        return activeVersionNo;
    }

    public void setActiveVersionNo(Integer activeVersionNo) {
        this.activeVersionNo = activeVersionNo;
    }

    public BigDecimal getEffectiveNetWeight() {
        return effectiveNetWeight;
    }

    public void setEffectiveNetWeight(BigDecimal effectiveNetWeight) {
        this.effectiveNetWeight = effectiveNetWeight;
    }

    public BigDecimal getEffectiveTotalAmount() {
        return effectiveTotalAmount;
    }

    public void setEffectiveTotalAmount(BigDecimal effectiveTotalAmount) {
        this.effectiveTotalAmount = effectiveTotalAmount;
    }

    public Long getLockVersion() {
        return lockVersion;
    }

    public void setLockVersion(Long lockVersion) {
        this.lockVersion = lockVersion;
    }
}
