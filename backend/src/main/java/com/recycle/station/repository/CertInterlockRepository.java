package com.recycle.station.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import com.recycle.station.domain.CertInterlock;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface CertInterlockRepository extends JpaRepository<CertInterlock, Long> {

    /**
     * 联锁门行锁：派车确认 / 复核生效 / 撤销 / 在途回场都先抢它。
     * 抢锁事务排队执行，因此"资质变更"与"派车确认"必有明确先后，
     * 不会出现车已出发却找不到一组当时有效资料的中间态。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "10000"))
    @Query("SELECT i FROM CertInterlock i WHERE i.id = 1")
    CertInterlock lockSingleton();
}
