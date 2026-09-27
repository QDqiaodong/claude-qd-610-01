package com.recycle.station.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.recycle.station.domain.CredentialDoc;

import jakarta.persistence.LockModeType;

public interface CredentialDocRepository extends JpaRepository<CredentialDoc, Long> {

    /** 一条版本链：按版本号升序 */
    List<CredentialDoc> findByGroupUuidOrderByVersionNoAsc(String groupUuid);

    /** 某对象（司机/车辆）的全部证照版本，列表页用 */
    List<CredentialDoc> findByOwnerTypeAndOwnerIdOrderByDocTypeAscVersionNoAsc(String ownerType, Long ownerId);

    List<CredentialDoc> findByStatusOrderBySubmittedAtAsc(String status);

    long countByOwnerTypeAndOwnerIdAndDocType(String ownerType, Long ownerId, String docType);

    Optional<CredentialDoc> findByOwnerTypeAndOwnerIdAndDocTypeAndStatus(
            String ownerType, Long ownerId, String docType, String status);

    /** 派车/换版事务内对当前生效行加行锁，保证资质变更与派车确认严格排队 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM CredentialDoc d WHERE d.ownerType = :ownerType AND d.ownerId = :ownerId "
            + "AND d.docType = :docType AND d.status = 'EFFECTIVE'")
    Optional<CredentialDoc> lockActive(@Param("ownerType") String ownerType,
                                       @Param("ownerId") Long ownerId,
                                       @Param("docType") String docType);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM CredentialDoc d WHERE d.id = :id")
    Optional<CredentialDoc> lockById(@Param("id") Long id);

    /** 到期扫描：全部生效版本（每日定时 + 服务启动时各扫一次） */
    List<CredentialDoc> findByStatus(String status);
}
