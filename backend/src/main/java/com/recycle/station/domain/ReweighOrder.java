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
import jakarta.persistence.Version;

/**
 * 复称单：一张磅单的一次"发起 → 补录 → 现场放行/复核 → 恢复结算"处理链。
 * <p>
 * 并发与幂等保证（全部落库，不靠内存锁，服务重启后依然成立）：
 * <ul>
 *   <li>{@code uk_open_reweigh}（DB 生成列 open_slot）保证同一磅单至多一条未结束复称，
 *       双窗口同时提交也只能插入一条；</li>
 *   <li>{@code uk_reweigh_idempotent}（client_request_id + initiator）保证重复点击 /
 *       请求超时后重试拿到同一处理结果；</li>
 *   <li>{@link #lockVersion} 乐观锁防止两条审核链路同时改判。</li>
 * </ul>
 */
@Entity
@Table(name = "reweigh_order")
public class ReweighOrder {

    /** 复称单号前缀 */
    public static final String NO_PREFIX = "FC";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reweigh_no", nullable = false, unique = true, length = 32)
    private String reweighNo;

    @Column(name = "ticket_no", nullable = false, length = 32)
    private String ticketNo;

    // ---------------- 原始快照（发起时从磅单原样拷贝，永久留痕） ----------------

    @Column(name = "origin_gross_weight", nullable = false, precision = 12, scale = 2)
    private BigDecimal originGrossWeight;

    @Column(name = "origin_tare_weight", nullable = false, precision = 12, scale = 2)
    private BigDecimal originTareWeight;

    @Column(name = "origin_net_weight", nullable = false, precision = 12, scale = 2)
    private BigDecimal originNetWeight;

    @Column(name = "origin_total_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal originTotalAmount;

    // ---------------- 复称三项重量（补录阶段补齐） ----------------

    @Column(name = "re_gross_weight", precision = 12, scale = 2)
    private BigDecimal reGrossWeight;

    @Column(name = "re_tare_weight", precision = 12, scale = 2)
    private BigDecimal reTareWeight;

    @Column(name = "re_net_weight", precision = 12, scale = 2)
    private BigDecimal reNetWeight;

    /** 复称净重 - 原净重（带正负） */
    @Column(name = "diff_net_weight", precision = 12, scale = 2)
    private BigDecimal diffNetWeight;

    /** 补录时的阈值判定：true 可现场放行，false 必须待复核 */
    @Column(name = "diff_within_threshold")
    private Boolean diffWithinThreshold;

    @Column(name = "diff_reason", length = 200)
    private String diffReason;

    /** INITIATED / AWAIT_RELEASE / AWAIT_REVIEW / RELEASED / APPROVED / REJECTED */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "initiator", nullable = false, length = 30)
    private String initiator;

    @Column(name = "initiated_at", nullable = false)
    private LocalDateTime initiatedAt;

    @Column(name = "supplemented_at")
    private LocalDateTime supplementedAt;

    @Column(name = "releaser", length = 30)
    private String releaser;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "reviewer", length = 30)
    private String reviewer;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /** APPROVED / REJECTED */
    @Column(name = "review_result", length = 20)
    private String reviewResult;

    @Column(name = "reject_reason", length = 200)
    private String rejectReason;

    /** 放行/通过后实际采用的磅单版本号 */
    @Column(name = "adopted_version_no")
    private Integer adoptedVersionNo;

    /** 幂等键：同一发起人同一请求只落一条 */
    @Column(name = "client_request_id", length = 64)
    private String clientRequestId;

    /** 是否未结束：true 处理中（磅单冻结），false 已终审 */
    @Column(name = "open_flag", nullable = false)
    private boolean openFlag = true;

    /** 乐观锁：已经终审的记录不得被并发二次改判 */
    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 明细快照 / 重分配方案，随复称单一并存取 */
    @OneToMany(mappedBy = "reweigh", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<ReweighLine> lines = new ArrayList<>();

    /** 状态轨迹，只追加 */
    @OneToMany(mappedBy = "reweigh", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<ReweighEvent> events = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getReweighNo() {
        return reweighNo;
    }

    public void setReweighNo(String reweighNo) {
        this.reweighNo = reweighNo;
    }

    public String getTicketNo() {
        return ticketNo;
    }

    public void setTicketNo(String ticketNo) {
        this.ticketNo = ticketNo;
    }

    public BigDecimal getOriginGrossWeight() {
        return originGrossWeight;
    }

    public void setOriginGrossWeight(BigDecimal originGrossWeight) {
        this.originGrossWeight = originGrossWeight;
    }

    public BigDecimal getOriginTareWeight() {
        return originTareWeight;
    }

    public void setOriginTareWeight(BigDecimal originTareWeight) {
        this.originTareWeight = originTareWeight;
    }

    public BigDecimal getOriginNetWeight() {
        return originNetWeight;
    }

    public void setOriginNetWeight(BigDecimal originNetWeight) {
        this.originNetWeight = originNetWeight;
    }

    public BigDecimal getOriginTotalAmount() {
        return originTotalAmount;
    }

    public void setOriginTotalAmount(BigDecimal originTotalAmount) {
        this.originTotalAmount = originTotalAmount;
    }

    public BigDecimal getReGrossWeight() {
        return reGrossWeight;
    }

    public void setReGrossWeight(BigDecimal reGrossWeight) {
        this.reGrossWeight = reGrossWeight;
    }

    public BigDecimal getReTareWeight() {
        return reTareWeight;
    }

    public void setReTareWeight(BigDecimal reTareWeight) {
        this.reTareWeight = reTareWeight;
    }

    public BigDecimal getReNetWeight() {
        return reNetWeight;
    }

    public void setReNetWeight(BigDecimal reNetWeight) {
        this.reNetWeight = reNetWeight;
    }

    public BigDecimal getDiffNetWeight() {
        return diffNetWeight;
    }

    public void setDiffNetWeight(BigDecimal diffNetWeight) {
        this.diffNetWeight = diffNetWeight;
    }

    public Boolean getDiffWithinThreshold() {
        return diffWithinThreshold;
    }

    public void setDiffWithinThreshold(Boolean diffWithinThreshold) {
        this.diffWithinThreshold = diffWithinThreshold;
    }

    public String getDiffReason() {
        return diffReason;
    }

    public void setDiffReason(String diffReason) {
        this.diffReason = diffReason;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getInitiator() {
        return initiator;
    }

    public void setInitiator(String initiator) {
        this.initiator = initiator;
    }

    public LocalDateTime getInitiatedAt() {
        return initiatedAt;
    }

    public void setInitiatedAt(LocalDateTime initiatedAt) {
        this.initiatedAt = initiatedAt;
    }

    public LocalDateTime getSupplementedAt() {
        return supplementedAt;
    }

    public void setSupplementedAt(LocalDateTime supplementedAt) {
        this.supplementedAt = supplementedAt;
    }

    public String getReleaser() {
        return releaser;
    }

    public void setReleaser(String releaser) {
        this.releaser = releaser;
    }

    public LocalDateTime getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(LocalDateTime releasedAt) {
        this.releasedAt = releasedAt;
    }

    public String getReviewer() {
        return reviewer;
    }

    public void setReviewer(String reviewer) {
        this.reviewer = reviewer;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getReviewResult() {
        return reviewResult;
    }

    public void setReviewResult(String reviewResult) {
        this.reviewResult = reviewResult;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public Integer getAdoptedVersionNo() {
        return adoptedVersionNo;
    }

    public void setAdoptedVersionNo(Integer adoptedVersionNo) {
        this.adoptedVersionNo = adoptedVersionNo;
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
    }

    public boolean isOpenFlag() {
        return openFlag;
    }

    public void setOpenFlag(boolean openFlag) {
        this.openFlag = openFlag;
    }

    public Long getLockVersion() {
        return lockVersion;
    }

    public void setLockVersion(Long lockVersion) {
        this.lockVersion = lockVersion;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public List<ReweighLine> getLines() {
        return lines;
    }

    public void setLines(List<ReweighLine> lines) {
        this.lines = lines;
    }

    public List<ReweighEvent> getEvents() {
        return events;
    }

    public void setEvents(List<ReweighEvent> events) {
        this.events = events;
    }
}
