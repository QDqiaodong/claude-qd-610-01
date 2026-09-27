package com.recycle.station.domain;

import java.time.LocalDate;

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
 * 派车资格快照：派车确认瞬间把每份采用的证照版本原样固化。
 * 证照之后换版、撤销、到期都不影响历史出车；查询某次出车当时资料只读本快照。
 */
@Entity
@Table(name = "dispatch_credential_snapshot")
public class DispatchCredentialSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id")
    private DispatchTrip trip;

    @Column(name = "owner_type", nullable = false, length = 10)
    private String ownerType;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "owner_name", nullable = false, length = 30)
    private String ownerName;

    @Column(name = "doc_type", nullable = false, length = 30)
    private String docType;

    @Column(name = "doc_id", nullable = false)
    private Long docId;

    /** 固化的版本号与业务键，快照页直接展示“本次采用 V3” */
    @Column(name = "doc_version_no", nullable = false)
    private Integer docVersionNo;

    @Column(name = "doc_number", nullable = false, length = 60)
    private String docNumber;

    @Column(name = "license_class", length = 10)
    private String licenseClass;

    @Column(name = "subject_desc", length = 60)
    private String subjectDesc;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_until", nullable = false)
    private LocalDate validUntil;

    @Column(name = "attachment_note", length = 200)
    private String attachmentNote;

    /** 派车当时该版本的状态（恒为 EFFECTIVE，留字段方便日后审计） */
    @Column(name = "doc_status_at_dispatch", nullable = false, length = 20)
    private String docStatusAtDispatch;

    @Column(name = "handler", length = 30)
    private String handler;

    @Column(name = "reviewer", length = 30)
    private String reviewer;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public DispatchTrip getTrip() {
        return trip;
    }

    public void setTrip(DispatchTrip trip) {
        this.trip = trip;
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

    public Long getDocId() {
        return docId;
    }

    public void setDocId(Long docId) {
        this.docId = docId;
    }

    public Integer getDocVersionNo() {
        return docVersionNo;
    }

    public void setDocVersionNo(Integer docVersionNo) {
        this.docVersionNo = docVersionNo;
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

    public String getDocStatusAtDispatch() {
        return docStatusAtDispatch;
    }

    public void setDocStatusAtDispatch(String docStatusAtDispatch) {
        this.docStatusAtDispatch = docStatusAtDispatch;
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
}
