package com.recycle.station.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import com.recycle.station.domain.DispatchTrip;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface DispatchTripRepository extends JpaRepository<DispatchTrip, Long> {

    /** 车辆当前未结束出车（OPEN / PENDING_DISPOSAL）；uk_trip_open 保证至多一条 */
    Optional<DispatchTrip> findOpenByVehicleId(Long vehicleId);

    @Query("SELECT t FROM DispatchTrip t WHERE t.vehicleId = :vehicleId "
            + "AND t.status IN ('OPEN','PENDING_DISPOSAL')")
    List<DispatchTrip> findOpenByVehicleIdList(Long vehicleId);

    List<DispatchTrip> findByStatusOrderByDispatchedAtAsc(String status);

    List<DispatchTrip> findByStatusOrderByDispatchedAtDesc(String status);

    List<DispatchTrip> findByVehicleIdOrderByDispatchedAtDesc(Long vehicleId);

    List<DispatchTrip> findByDriverIdOrderByDispatchedAtDesc(Long driverId);

    List<DispatchTrip> findTop50ByOrderByDispatchedAtDesc();

    /** 派车单号生成：查当天最大序号 / 撞号 */
    long countByTripNoStartingWith(String prefix);

    boolean existsByTripNo(String tripNo);

    long countByStatus(String status);

    /** 撤销生效证照时列出仍在使用该资料的未结束出车（快照明细 series/document 命中） */
    @Query("SELECT DISTINCT t FROM DispatchTrip t, DispatchTripCert c "
            + "WHERE c.trip.id = t.id AND c.seriesId = :seriesId AND c.documentId = :documentId "
            + "AND t.status IN ('OPEN','PENDING_DISPOSAL') ORDER BY t.dispatchedAt ASC")
    List<DispatchTrip> findOpenTripsUsingCert(Long seriesId, Long documentId);

    /** 行锁：派车 / 回场持有车辆出车单行，配合联锁门串行化 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "10000"))
    @Query("SELECT t FROM DispatchTrip t WHERE t.vehicleId = :vehicleId "
            + "AND t.status IN ('OPEN','PENDING_DISPOSAL')")
    List<DispatchTrip> lockOpenByVehicleId(Long vehicleId);
}
