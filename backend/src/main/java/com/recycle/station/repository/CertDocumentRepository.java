package com.recycle.station.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.recycle.station.domain.CertDocument;

public interface CertDocumentRepository extends JpaRepository<CertDocument, Long> {

    List<CertDocument> findBySeriesIdOrderByVersionNoAsc(Long seriesId);

    Optional<CertDocument> findByIdAndSeriesId(Long id, Long seriesId);

    List<CertDocument> findByStatusOrderByUpdatedAtAsc(String status);

    List<CertDocument> findBySubjectTypeOrderBySubjectIdAscCertTypeAscVersionNoAsc(String subjectType);

    /** 到期扫描：所有仍生效但有效期止日已过的资料 */
    @org.springframework.data.jpa.repository.Query(
            "SELECT d FROM CertDocument d WHERE d.status = 'EFFECTIVE' AND d.validTo < :today ORDER BY d.id ASC")
    List<CertDocument> findExpiredEffective(java.time.LocalDate today);

    /** 撤销生效证照后，反查正在使用该版本的未结束出车（联表在 service 里做） */
    List<CertDocument> findBySeriesIdAndStatusOrderByVersionNoAsc(Long seriesId, String status);
}
