package com.recycle.station.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.recycle.station.domain.ReweighOrder;

/**
 * 复称单仓储。"同一磅单未结束复称唯一"的最终防线在数据库 uk_open_reweigh，
 * 这里的计数查询只用于在插入前给出友好的业务提示。
 */
public interface ReweighOrderRepository extends JpaRepository<ReweighOrder, Long> {

    ReweighOrder findByReweighNo(String reweighNo);

    /** 幂等：同一发起人 + 同一客户端请求键只对应一条复称单 */
    ReweighOrder findByClientRequestIdAndInitiator(String clientRequestId, String initiator);

    /** 该磅单是否已有未结束复称（处理中状态集见 StationDict） */
    long countByTicketNoAndOpenFlagTrue(String ticketNo);

    /** 磅单当前的未结束复称（至多一条，唯一索引兜底） */
    List<ReweighOrder> findByTicketNoAndOpenFlagTrue(String ticketNo);

    /** 复称台队列：未结束在前，同状态按发起时间升序（等得最久的排前面） */
    @org.springframework.data.jpa.repository.Query("SELECT r FROM ReweighOrder r ORDER BY r.openFlag DESC, r.initiatedAt ASC")
    List<ReweighOrder> findAllForBoard();
}
