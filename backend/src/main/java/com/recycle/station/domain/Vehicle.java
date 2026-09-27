package com.recycle.station.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 收运车辆与司机（车牌 + 司机 + 载重 + 状态 + 今日趟次）。
 */
@Entity
@Table(name = "vehicle")
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plate_no", nullable = false, unique = true, length = 20)
    private String plateNo;

    @Column(name = "driver_name", nullable = false, length = 30)
    private String driverName;

    @Column(name = "phone", length = 20)
    private String phone;

    /** 额定载重（吨） */
    @Column(name = "load_capacity", nullable = false, precision = 8, scale = 2)
    private BigDecimal loadCapacity;

    /** IDLE 待命 / ON_ROAD 在途 / REPAIR 维修 */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 派车所需最低准驾车型（C1/B2/A2/A1），由载重推定、可人工调整 */
    @Column(name = "required_license_class", length = 10)
    private String requiredLicenseClass;

    /** 待调度处置：在途期间其生效证照被撤销/换版时置 1，回场清零 */
    @Column(name = "pending_dispatch_flag", nullable = false)
    private Boolean pendingDispatchFlag = false;

    /** 待调度处置原因（哪份证照、何时被撤销/替代），给调度员的人话 */
    @Column(name = "pending_dispatch_reason", length = 200)
    private String pendingDispatchReason;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    @Column(name = "trip_date")
    private LocalDate tripDate;

    /** 今日趟次（每天第一次派车归零重算） */
    @Column(name = "today_trips", nullable = false)
    private Integer todayTrips;

    @Column(name = "remark", length = 100)
    private String remark;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPlateNo() {
        return plateNo;
    }

    public void setPlateNo(String plateNo) {
        this.plateNo = plateNo;
    }

    public String getDriverName() {
        return driverName;
    }

    public void setDriverName(String driverName) {
        this.driverName = driverName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public BigDecimal getLoadCapacity() {
        return loadCapacity;
    }

    public void setLoadCapacity(BigDecimal loadCapacity) {
        this.loadCapacity = loadCapacity;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRequiredLicenseClass() {
        return requiredLicenseClass;
    }

    public void setRequiredLicenseClass(String requiredLicenseClass) {
        this.requiredLicenseClass = requiredLicenseClass;
    }

    public Boolean getPendingDispatchFlag() {
        return pendingDispatchFlag;
    }

    public void setPendingDispatchFlag(Boolean pendingDispatchFlag) {
        this.pendingDispatchFlag = pendingDispatchFlag;
    }

    public String getPendingDispatchReason() {
        return pendingDispatchReason;
    }

    public void setPendingDispatchReason(String pendingDispatchReason) {
        this.pendingDispatchReason = pendingDispatchReason;
    }

    public Long getLockVersion() {
        return lockVersion;
    }

    public void setLockVersion(Long lockVersion) {
        this.lockVersion = lockVersion;
    }

    public LocalDate getTripDate() {
        return tripDate;
    }

    public void setTripDate(LocalDate tripDate) {
        this.tripDate = tripDate;
    }

    public Integer getTodayTrips() {
        return todayTrips;
    }

    public void setTodayTrips(Integer todayTrips) {
        this.todayTrips = todayTrips;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
