package com.recycle.station.domain;

import java.time.LocalDateTime;

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

import java.util.ArrayList;
import java.util.List;

/**
 * 一次出车（车辆 待命→在途 必须走派车确认，回场结束）。
 * <p>
 * 派车确认的同一事务里，把当时采用的每份证照版本拷进 dispatch_credential_snapshot
 * （派车资格快照）；之后证照换版/撤销/到期都不改写本车历史快照，
 * 但车辆的待调度处置标志（pending_dispatch_flag）会提示调度员处理仍在途的车。
 */
@Entity
@Table(name = "dispatch_trip")
public class DispatchTrip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 派车单号：PC + yyyyMMdd + 三位序号 */
    @Column(name = "trip_no", nullable = false, unique = true, length = 32)
    private String tripNo;

    @Column(name = "vehicle_id", nullable = false)
    private Long vehicleId;

    @Column(name = "plate_no", nullable = false, length = 20)
    private String plateNo;

    /** 本次司机（派车确认时选定，不再随车辆默认司机变更） */
    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Column(name = "driver_name", nullable = false, length = 30)
    private String driverName;

    /** 派车时刻该司机的准驾车型（取自生效驾驶证快照口径） */
    @Column(name = "driver_license_class", length = 10)
    private String driverLicenseClass;

    /** 车辆要求的最低准驾车型 */
    @Column(name = "required_license_class", length = 10)
    private String requiredLicenseClass;

    @Column(name = "dispatched_at", nullable = false)
    private LocalDateTime dispatchedAt;

    @Column(name = "planned_return_at", nullable = false)
    private LocalDateTime plannedReturnAt;

    @Column(name = "returned_at")
    private LocalDateTime returnedAt;

    /** ON_ROAD 在途 / RETURNED 已回场 */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /**
     * 证照联锁处置：在途期间所采用的生效证照被撤销（或新版本替代）时置 1，
     * 表示“待调度处置”；历史快照不变，仅提示调度员联系回场。回场时清零。
     */
    @Column(name = "pending_dispatch_flag", nullable = false)
    private Boolean pendingDispatchFlag = false;

    @Column(name = "pending_reason", length = 200)
    private String pendingReason;

    @Column(name = "dispatcher", nullable = false, length = 30)
    private String dispatcher;

    @Column(name = "remark", length = 100)
    private String remark;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("ownerType ASC, docType ASC")
    private List<DispatchCredentialSnapshot> snapshots = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTripNo() {
        return tripNo;
    }

    public void setTripNo(String tripNo) {
        this.tripNo = tripNo;
    }

    public Long getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(Long vehicleId) {
        this.vehicleId = vehicleId;
    }

    public String getPlateNo() {
        return plateNo;
    }

    public void setPlateNo(String plateNo) {
        this.plateNo = plateNo;
    }

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public String getDriverName() {
        return driverName;
    }

    public void setDriverName(String driverName) {
        this.driverName = driverName;
    }

    public String getDriverLicenseClass() {
        return driverLicenseClass;
    }

    public void setDriverLicenseClass(String driverLicenseClass) {
        this.driverLicenseClass = driverLicenseClass;
    }

    public String getRequiredLicenseClass() {
        return requiredLicenseClass;
    }

    public void setRequiredLicenseClass(String requiredLicenseClass) {
        this.requiredLicenseClass = requiredLicenseClass;
    }

    public LocalDateTime getDispatchedAt() {
        return dispatchedAt;
    }

    public void setDispatchedAt(LocalDateTime dispatchedAt) {
        this.dispatchedAt = dispatchedAt;
    }

    public LocalDateTime getPlannedReturnAt() {
        return plannedReturnAt;
    }

    public void setPlannedReturnAt(LocalDateTime plannedReturnAt) {
        this.plannedReturnAt = plannedReturnAt;
    }

    public LocalDateTime getReturnedAt() {
        return returnedAt;
    }

    public void setReturnedAt(LocalDateTime returnedAt) {
        this.returnedAt = returnedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Boolean getPendingDispatchFlag() {
        return pendingDispatchFlag;
    }

    public void setPendingDispatchFlag(Boolean pendingDispatchFlag) {
        this.pendingDispatchFlag = pendingDispatchFlag;
    }

    public String getPendingReason() {
        return pendingReason;
    }

    public void setPendingReason(String pendingReason) {
        this.pendingReason = pendingReason;
    }

    public String getDispatcher() {
        return dispatcher;
    }

    public void setDispatcher(String dispatcher) {
        this.dispatcher = dispatcher;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Long getLockVersion() {
        return lockVersion;
    }

    public void setLockVersion(Long lockVersion) {
        this.lockVersion = lockVersion;
    }

    public List<DispatchCredentialSnapshot> getSnapshots() {
        return snapshots;
    }

    public void setSnapshots(List<DispatchCredentialSnapshot> snapshots) {
        this.snapshots = snapshots;
    }
}
