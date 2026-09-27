package com.recycle.station.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.recycle.station.domain.Driver;

public interface DriverRepository extends JpaRepository<Driver, Long> {

    Driver findByDriverNo(String driverNo);

    List<Driver> findByStatusOrderByIdAsc(String status);

    List<Driver> findAllByOrderByIdAsc();
}
