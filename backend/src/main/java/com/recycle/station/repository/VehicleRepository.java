package com.recycle.station.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.recycle.station.domain.Vehicle;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    Vehicle findByPlateNo(String plateNo);

    List<Vehicle> findByStatusOrderByTodayTripsDesc(String status);

    long countByStatus(String status);
}
