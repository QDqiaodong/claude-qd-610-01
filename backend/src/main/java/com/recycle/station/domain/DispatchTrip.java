package com.recycle.station.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 出车单：车辆 IDLE→在途 时生成一条，回场后 FINISHED。
 * <p>
 * 采用的派车资格快照存在 {@link DispatchTripCert}（出车那一刻逐份拷贝），
 * 之后证照换版 / 撤销 / 到期都不改快照，已经发生的出车永远能还原当时依据。
 * <p>
 * uk_trip_open（open_slot 生成列）保证同一车辆至多一条未结束出车；
 * 生效证照被撤销时，引用该版本的在途车被置 PENDING_DISPOSAL（待调度处置），
 * 其未结束状态仍占用 open_slot，车辆照样不能再次派出。
 */
@Entity
@Table(name = "dispatch_trip")
public class DispatchTrip {

    /** 在途 */
    public static final String OPEN = "OPEN";

    /** 已回场 */
    public static final String FINISHED = "FINISHED";

    /** 在途但所用生效证照被撤销，待调度处置 */
    public static final String PENDING_DISPOSAL = "PENDING_DISPOSAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trip_no", nullable = false, unique = true, length = 32)
    private String tripNo;

    @Column(name = "vehicle_id", nullable = false)
    private Long vehicleId;

    @Column(name = "plate_no", nullable = false, length = 20)
    private String plateNo;

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Column(name = "driver_name", nullable = false, length = 30)
    private String driverName;

    @Column(name = "dispatch_operator", nullable = false, length = 30)
    private String dispatchOperator;

    /** 计划回场：所有必需证照必须持续有效到这一时刻，只看"今天"不算数 */
    @Column(name = "planned_return_at", nullable = false)
    private LocalDateTime plannedReturnAt;

    @Column(name = "dispatched_at", nullable = false)
    private LocalDateTime dispatchedAt;

    @Column(name = "returned_at")
    private LocalDateTime returnedAt;

    /** OPEN / FINISHED / PENDING_DISPOSAL */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "disposal_reason", length = 200)
    private String disposalReason;

    @Column(name = "disposition_note", length = 200)
    private String dispositionNote;

    @Column(name = "disposition_at")
    private LocalDateTime dispositionAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    /** 派车资格快照（不可变） */
    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<DispatchTripCert> certs = new ArrayList<>();

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

    public String getDispatchOperator() {
        return dispatchOperator;
    }

    public void setDispatchOperator(String dispatchOperator) {
        this.dispatchOperator = dispatchOperator;
    }

    public LocalDateTime getPlannedReturnAt() {
        return plannedReturnAt;
    }

    public void setPlannedReturnAt(LocalDateTime plannedReturnAt) {
        this.plannedReturnAt = plannedReturnAt;
    }

    public LocalDateTime getDispatchedAt() {
        return dispatchedAt;
    }

    public void setDispatchedAt(LocalDateTime dispatchedAt) {
        this.dispatchedAt = dispatchedAt;
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

    public String getDisposalReason() {
        return disposalReason;
    }

    public void setDisposalReason(String disposalReason) {
        this.disposalReason = disposalReason;
    }

    public String getDispositionNote() {
        return dispositionNote;
    }

    public void setDispositionNote(String dispositionNote) {
        this.dispositionNote = dispositionNote;
    }

    public LocalDateTime getDispositionAt() {
        return dispositionAt;
    }

    public void setDispositionAt(LocalDateTime dispositionAt) {
        this.dispositionAt = dispositionAt;
    }

    public Long getLockVersion() {
        return lockVersion;
    }

    public void setLockVersion(Long lockVersion) {
        this.lockVersion = lockVersion;
    }

    public List<DispatchTripCert> getCerts() {
        return certs;
    }

    public void setCerts(List<DispatchTripCert> certs) {
        this.certs = certs;
    }
}
