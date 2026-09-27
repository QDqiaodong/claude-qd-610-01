package com.recycle.station.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 证照版本链：一个主体（司机 / 车辆）的一类证照一条。
 * <p>
 * {@code currentDocumentId} 指向当前生效版本；从未生效过（全部到期 / 驳回 / 撤销）时为 NULL。
 * 旧版本永久保留在 cert_document 里，"换版"只是把指针挪到新版本并把旧版本置 SUPERSEDED，
 * 不覆盖任何旧行。{@link #lockVersion} 与联锁门共同保证并发复核只可能留下一份生效版本。
 */
@Entity
@Table(name = "cert_series")
public class CertSeries {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "series_no", nullable = false, unique = true, length = 40)
    private String seriesNo;

    /** DRIVER 司机证照 / VEHICLE 车辆证照 */
    @Column(name = "subject_type", nullable = false, length = 10)
    private String subjectType;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    /** 主体名称快照（司机姓名 / 车牌），避免主体档案改名后证照链看不出归属 */
    @Column(name = "subject_name", nullable = false, length = 50)
    private String subjectName;

    /** DRIVER_LICENSE / QUALIFICATION / ANNUAL_INSPECTION / INSURANCE / ROAD_TRANSPORT_PERMIT */
    @Column(name = "cert_type", nullable = false, length = 30)
    private String certType;

    /** 当前生效版本；从未生效过为 null */
    @Column(name = "current_document_id")
    private Long currentDocumentId;

    /** 下一版版本号（历史版本号永久占用，不复用） */
    @Column(name = "next_version_no", nullable = false)
    private Integer nextVersionNo;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSeriesNo() {
        return seriesNo;
    }

    public void setSeriesNo(String seriesNo) {
        this.seriesNo = seriesNo;
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

    public Long getCurrentDocumentId() {
        return currentDocumentId;
    }

    public void setCurrentDocumentId(Long currentDocumentId) {
        this.currentDocumentId = currentDocumentId;
    }

    public Integer getNextVersionNo() {
        return nextVersionNo;
    }

    public void setNextVersionNo(Integer nextVersionNo) {
        this.nextVersionNo = nextVersionNo;
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
}
