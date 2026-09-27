package com.recycle.station.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 司机花名册。车辆默认司机仍保留在 vehicle 表上，调度派车时以本花名册选择
 * 本次司机；准驾资质的权威来源是该司机当前生效的驾驶证证照（CRED_DRIVER_LICENSE），
 * 这里的 license_class 只是档案上的登记口径，供列表展示与快速筛选。
 */
@Entity
@Table(name = "driver")
public class Driver {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "driver_name", nullable = false, length = 30)
    private String driverName;

    @Column(name = "phone", length = 20)
    private String phone;

    /** 登记准驾车型：C1/B2/A2/A1 等（权威口径以生效驾驶证证照为准） */
    @Column(name = "license_class", length = 10)
    private String licenseClass;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "remark", length = 100)
    private String remark;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getLicenseClass() {
        return licenseClass;
    }

    public void setLicenseClass(String licenseClass) {
        this.licenseClass = licenseClass;
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
}
