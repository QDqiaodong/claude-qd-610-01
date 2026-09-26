package com.recycle.station.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.recycle.station.domain.WeighTicketVersion;

public interface WeighTicketVersionRepository extends JpaRepository<WeighTicketVersion, Long> {

    List<WeighTicketVersion> findByTicketNoOrderByVersionNoAsc(String ticketNo);

    WeighTicketVersion findByTicketNoAndVersionNo(String ticketNo, Integer versionNo);

    long countByTicketNo(String ticketNo);
}
