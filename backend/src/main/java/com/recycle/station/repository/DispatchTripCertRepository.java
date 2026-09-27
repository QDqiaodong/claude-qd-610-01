package com.recycle.station.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.recycle.station.domain.DispatchTripCert;

public interface DispatchTripCertRepository extends JpaRepository<DispatchTripCert, Long> {

    /** tripId 穿越关联属性 trip 的 id 派生 */
    List<DispatchTripCert> findByTripIdOrderByIdAsc(Long tripId);
}
