package com.recycle.station.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.recycle.station.domain.ReweighEvent;

public interface ReweighEventRepository extends JpaRepository<ReweighEvent, Long> {
}
