package com.recycle.station.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import com.recycle.station.domain.CertSeries;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface CertSeriesRepository extends JpaRepository<CertSeries, Long> {

    Optional<CertSeries> findBySubjectTypeAndSubjectIdAndCertType(String subjectType, Long subjectId, String certType);

    List<CertSeries> findBySubjectTypeOrderBySubjectIdAscCertTypeAsc(String subjectType);

    List<CertSeries> findAllByOrderBySubjectTypeAscSubjectIdAscCertTypeAsc();

    List<CertSeries> findByCurrentDocumentId(Long currentDocumentId);

    /** 行锁：复核生效 / 撤销 / 到期扫描改版本链指针时持有，串行化同一条链上的并发操作 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT s FROM CertSeries s WHERE s.id = :id")
    CertSeries lockById(Long id);
}
