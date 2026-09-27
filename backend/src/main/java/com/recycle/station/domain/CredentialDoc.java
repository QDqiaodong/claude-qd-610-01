package com.recycle.station.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 证照有效期联锁台——证照资料的一个版本。
 * <p>
 * 每个业务键（owner_type + owner_id + doc_type）是一条版本链：草稿/待复核/驳回/撤销/到期/
 * 被替代的历史行永久保留、永不覆盖；同一业务键至多一行 EFFECTIVE，
 * 由 {@code uk_cred_active_slot}（active_slot 生成列 + 唯一索引）在数据库层兜底，
 * 两名复核人同时放行新旧版本也只能落一份当前生效版本。
 * <p>
 * 派车只认当前生效版本；出车时把当次采用的版本固化进派车资格快照，之后换版不影响历史出车。
 */
@Entity
@Table(name = "credential_doc")
public class CredentialDoc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** DRIVER 司机证照 / VEHICLE 车辆证照 */
    @Column(name = "owner_type", nullable = false, length = 10)
    private String ownerType;

    /** driver.id / vehicle.id */
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    /** 快照冗余：司机姓名 / 车牌，列表展示不必回表 */
    @Column(name = "owner_name", nullable = false, length = 30)
    private String ownerName;

    /**
     * 证件类型：
     * 司机：CRED_DRIVER_LICENSE 驾驶证 / CRED_QUALIFICATION 从业资格证；
     * 车辆：CRED_ANNUAL_INSPECTION 年检 / CRED_INSURANCE 交强险 / CRED_TRANSPORT_PERMIT 道路运输证。
     */
    @Column(name = "doc_type", nullable = false, length = 30)
    private String docType;

    /** 版本号：同一业务键从 1 递增，永不复用 */
    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    /** 同一条版本链的归并号（V1 落库时生成的 UUID，新版本沿用），供“查看新旧版本差异”整链取数 */
    @Column(name = "group_uuid", nullable = false, length = 36)
    private String groupUuid;

    @Column(name = "doc_number", nullable = false, length = 60)
    private String docNumber;

    /** 准驾车型（驾驶证，如 B2）；车辆证一律为空 */
    @Column(name = "license_class", length = 10)
    private String licenseClass;

    /** 适用车型/车辆的文字说明（车辆证冗余车型口径，司机证为空） */
    @Column(name = "subject_desc", length = 60)
    private String subjectDesc;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_until", nullable = false)
    private LocalDate validUntil;

    /** 附件说明（扫描件文件名、份数、纸质存放位置等，本系统不存二进制） */
    @Column(name = "attachment_note", length = 200)
    private String attachmentNote;

    /**
     * DRAFT 草稿 / PENDING_REVIEW 待复核 / EFFECTIVE 生效 / REJECTED 驳回 /
     * REVOKED 撤销 / EXPIRED 到期 / SUPERSEDED 被新版本替代
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "handler", nullable = false, length = 30)
    private String handler;

    @Column(name = "reviewer", length = 30)
    private String reviewer;

    /** 驳回 / 撤销原因（两种动作必填，随版本永久留痕） */
    @Column(name = "reason", length = 200)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /** 被新版本替代/到期/撤销时的时间（轨迹排序用） */
    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    /**
     * 生成列（DDL 定义，JPA 不写入）：EFFECTIVE 时等于业务键，其余为 NULL。
     * uk_cred_active_slot 唯一索引据此保证“一个业务键只有一份生效版本”。
     */
    @Column(name = "active_slot", insertable = false, updatable = false, length = 80)
    private String activeSlot;

    /** JPA 乐观锁：旧页面/超时重试/双复核并发时据此判 VERSION_CONFLICT */
    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOwnerType() {
        return ownerType;
    }

    public void setOwnerType(String ownerType) {
        this.ownerType = ownerType;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public String getDocType() {
        return docType;
    }

    public void setDocType(String docType) {
        this.docType = docType;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getGroupUuid() {
        return groupUuid;
    }

    public void setGroupUuid(String groupUuid) {
        this.groupUuid = groupUuid;
    }

    public String getDocNumber() {
        return docNumber;
    }

    public void setDocNumber(String docNumber) {
        this.docNumber = docNumber;
    }

    public String getLicenseClass() {
        return licenseClass;
    }

    public void setLicenseClass(String licenseClass) {
        this.licenseClass = licenseClass;
    }

    public String getSubjectDesc() {
        return subjectDesc;
    }

    public void setSubjectDesc(String subjectDesc) {
        this.subjectDesc = subjectDesc;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(LocalDate validFrom) {
        this.validFrom = validFrom;
    }

    public LocalDate getValidUntil() {
        return validUntil;
    }

    public void setValidUntil(LocalDate validUntil) {
        this.validUntil = validUntil;
    }

    public String getAttachmentNote() {
        return attachmentNote;
    }

    public void setAttachmentNote(String attachmentNote) {
        this.attachmentNote = attachmentNote;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getHandler() {
        return handler;
    }

    public void setHandler(String handler) {
        this.handler = handler;
    }

    public String getReviewer() {
        return reviewer;
    }

    public void setReviewer(String reviewer) {
        this.reviewer = reviewer;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(LocalDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(LocalDateTime closedAt) {
        this.closedAt = closedAt;
    }

    public String getActiveSlot() {
        return activeSlot;
    }

    public Long getLockVersion() {
        return lockVersion;
    }

    public void setLockVersion(Long lockVersion) {
        this.lockVersion = lockVersion;
    }
}
