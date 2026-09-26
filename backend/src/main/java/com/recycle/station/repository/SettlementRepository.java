package com.recycle.station.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.recycle.station.domain.Settlement;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    Settlement findBySettleNo(String settleNo);

    List<Settlement> findByTicketNoOrderBySettleTimeDesc(String ticketNo);
}
