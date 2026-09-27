package com.recycle.station.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.recycle.station.domain.DispatchCredentialSnapshot;

public interface DispatchCredentialSnapshotRepository extends JpaRepository<DispatchCredentialSnapshot, Long> {

    List<DispatchCredentialSnapshot> findByTripIdOrderByOwnerTypeAscDocTypeAsc(Long tripId);

    /**
     * 证照撤销/换版时查正在使用该资料版本的在途车辆：
     * 快照挂在 ON_ROAD 出车上且 docId 指向该版本。
     */
    List<DispatchCredentialSnapshot> findByDocId(Long docId);
}
