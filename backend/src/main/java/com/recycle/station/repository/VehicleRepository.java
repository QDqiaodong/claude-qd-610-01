package com.recycle.station.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.recycle.station.domain.Vehicle;

import jakarta.persistence.LockModeType;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    Vehicle findByPlateNo(String plateNo);

    List<Vehicle> findByStatusOrderByTodayTripsDesc(String status);

    long countByStatus(String status);

    /** 派车确认事务内对车辆行加写锁：重复派车 / 同时改状态严格排队 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM Vehicle v WHERE v.id = :id")
    Optional<Vehicle> lockById(@Param("id") Long id);
}
