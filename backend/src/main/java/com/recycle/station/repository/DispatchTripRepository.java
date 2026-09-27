package com.recycle.station.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.recycle.station.domain.DispatchTrip;

import jakarta.persistence.LockModeType;

public interface DispatchTripRepository extends JpaRepository<DispatchTrip, Long> {

    /** 未结束出车：一辆车至多一条（派车确认事务内还会加车辆行锁兜底） */
    Optional<DispatchTrip> findFirstByVehicleIdAndStatusOrderByIdDesc(Long vehicleId, String status);

    List<DispatchTrip> findByStatusOrderByDispatchedAtDesc(String status);

    List<DispatchTrip> findByVehicleIdOrderByDispatchedAtDesc(Long vehicleId);

    List<DispatchTrip> findTop20ByOrderByDispatchedAtDesc();

    /** 派车事务内锁在途行，配合车辆行锁消除重复派车 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM DispatchTrip t WHERE t.vehicleId = :vehicleId AND t.status = 'ON_ROAD'")
    List<DispatchTrip> lockOpenByVehicle(@Param("vehicleId") Long vehicleId);

    @Query("SELECT MAX(t.tripNo) FROM DispatchTrip t WHERE t.tripNo LIKE :prefix")
    String findMaxTripNo(@Param("prefix") String prefix);
}
