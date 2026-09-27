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
 * 派车资格快照明细：出车那一刻采用的每份必需证照版本原样拷贝。
 * <p>
 * 这是"可追溯链路"的落点：历史出车只认本表，不回查 cert_document 当前状态，
 * 因此后来换版 / 撤销 / 到期都不影响已经发生的出车。
 */
@Entity
@Table(name = "dispatch_trip_cert")
public class DispatchTripCert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", referencedColumnName = "id")
    private DispatchTrip trip;

    /** 版本链：撤销生效证照时据此反查哪些在途车正在使用 */
    @Column(name = "series_id", nullable = false)
    private Long seriesId;

    /** 采用的资料版本主键 */
    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "subject_type", nullable = false, length = 10)
    private String subjectType;

    @Column(name = "subject_name", nullable = false, length = 50)
    private String subjectName;

    @Column(name = "cert_type", nullable = false, length = 30)
    private String certType;

    @Column(name = "cert_no", nullable = false, length = 60)
    private String certNo;

    @Column(name = "license_class", length = 10)
    private String licenseClass;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to", nullable = false)
    private LocalDate validTo;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    /** 采用当时的资料状态（固定 EFFECTIVE，留痕用） */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

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

    public Long getSeriesId() {
        return seriesId;
    }

    public void setSeriesId(Long seriesId) {
        this.seriesId = seriesId;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public void setSubjectType(String subjectType) {
        this.subjectType = subjectType;
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

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
