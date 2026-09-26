package com.recycle.station.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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
