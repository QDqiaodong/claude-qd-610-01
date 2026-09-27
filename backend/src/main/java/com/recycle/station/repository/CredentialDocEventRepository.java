package com.recycle.station.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.recycle.station.domain.CredentialDocEvent;

public interface CredentialDocEventRepository extends JpaRepository<CredentialDocEvent, Long> {

    List<CredentialDocEvent> findByGroupUuidOrderByCreatedAtAscIdAsc(String groupUuid);

    List<CredentialDocEvent> findByDocIdOrderByCreatedAtAscIdAsc(Long docId);
}
