package com.recycle.station.domain;

import java.time.LocalDate;
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
 * 证照资料版本：cert_series 上每一版资料一行。
 * <p>
 * 资料内容（号码 / 准驾车型 / 起止日期 / 附件说明）录入后不允许再改；要改只能录新版本。
 * 状态沿完整生命周期推进：
 * DRAFT 草稿 → AWAIT_REVIEW 待复核 → EFFECTIVE 生效；
 * 待复核可 → REJECTED 驳回；生效后 → REVOKED 撤销 / EXPIRED 到期 / SUPERSEDED 被新版本替代。
 * <p>
 * 并发底线（全部落库，重启 / 多实例依旧成立）：
 * <ul>
 *   <li>uk_cert_effective_slot：每条版本链至多一份 EFFECTIVE，两名复核员并发处理新旧版本，
 *       晚提交的一方撞唯一索引，只能收到"资料版本已变化"；</li>
 *   <li>uk_cert_pending_slot：每条版本链至多一条 AWAIT_REVIEW；</li>
 *   <li>{@link #lockVersion} 乐观锁：旧页面 / 网络超时重试提交时状态早已推进，按过期操作拒绝。</li>
 * </ul>
 */
@Entity
@Table(name = "cert_document")
public class CertDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "series_id", nullable = false)
    private Long seriesId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "subject_type", nullable = false, length = 10)
    private String subjectType;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Column(name = "subject_name", nullable = false, length = 50)
    private String subjectName;

    @Column(name = "cert_type", nullable = false, length = 30)
    private String certType;

    @Column(name = "cert_no", nullable = false, length = 60)
    private String certNo;

    /** 准驾车型（仅驾驶证）：C2 / C1 / B2 / A2 */
    @Column(name = "license_class", length = 10)
    private String licenseClass;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to", nullable = false)
    private LocalDate validTo;

    @Column(name = "attachment_note", length = 200)
    private String attachmentNote;

    /** 经办人：录入人，不能复核自己录的资料 */
    @Column(name = "operator", nullable = false, length = 30)
    private String operator;

    /** 复核人：必须与经办人不同 */
    @Column(name = "reviewer", length = 30)
    private String reviewer;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "reject_reason", length = 200)
    private String rejectReason;

    @Column(name = "revoke_reason", length = 200)
    private String revokeReason;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "expired_at")
    private LocalDateTime expiredAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    /** 轨迹只追加，随资料版本一起装配给页面 */
    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    private List<CertEvent> events = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSeriesId() {
        return seriesId;
    }

    public void setSeriesId(Long seriesId) {
        this.seriesId = seriesId;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public void setSubjectType(String subjectType) {
        this.subjectType = subjectType;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(Long subjectId) {
        this.subjectId = subjectId;
    }

    public String getSubjectName() {
        return subjectName;
    }

    public void setSubjectName(String subjectName) {
        this.subjectName = subjectName;
    }

    public String getCertType() {
        return certType;
    }

    public void setCertType(String certType) {
        this.certType = certType;
    }

    public String getCertNo() {
        return certNo;
    }

    public void setCertNo(String certNo) {
        this.certNo = certNo;
    }

    public String getLicenseClass() {
        return licenseClass;
    }

    public void setLicenseClass(String licenseClass) {
        this.licenseClass = licenseClass;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(LocalDate validFrom) {
        this.validFrom = validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public void setValidTo(LocalDate validTo) {
        this.validTo = validTo;
    }

    public String getAttachmentNote() {
        return attachmentNote;
    }

    public void setAttachmentNote(String attachmentNote) {
        this.attachmentNote = attachmentNote;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getReviewer() {
        return reviewer;
    }

    public void setReviewer(String reviewer) {
        this.reviewer = reviewer;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public String getRevokeReason() {
        return revokeReason;
    }

    public void setRevokeReason(String revokeReason) {
        this.revokeReason = revokeReason;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(LocalDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }

    public LocalDateTime getExpiredAt() {
        return expiredAt;
    }

    public void setExpiredAt(LocalDateTime expiredAt) {
        this.expiredAt = expiredAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getLockVersion() {
        return lockVersion;
    }

    public void setLockVersion(Long lockVersion) {
        this.lockVersion = lockVersion;
    }

    public List<CertEvent> getEvents() {
        return events;
    }

    public void setEvents(List<CertEvent> events) {
        this.events = events;
    }
}
