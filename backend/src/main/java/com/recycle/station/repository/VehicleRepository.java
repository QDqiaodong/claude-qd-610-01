package com.recycle.station.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import com.recycle.station.domain.Vehicle;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    Vehicle findByPlateNo(String plateNo);

    List<Vehicle> findByStatusOrderByTodayTripsDesc(String status);

    long countByStatus(String status);

    /** 派车 / 回场 / 维修切换时持有的车辆行锁（联锁门之后第二把锁） */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "10000"))
    @Query("SELECT v FROM Vehicle v WHERE v.id = :id")
    Vehicle lockById(Long id);
}
