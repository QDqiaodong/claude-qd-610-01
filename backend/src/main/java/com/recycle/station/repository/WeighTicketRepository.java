package com.recycle.station.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.recycle.station.domain.WeighTicket;
import com.recycle.station.dto.CustomerRankRow;
import com.recycle.station.dto.DailySummaryRow;

import jakarta.persistence.LockModeType;

/**
 * 过磅单主表。
 * <p>
 * 本仓的报表一律走 JPQL 聚合（构造器表达式 + GROUP BY），不用 Specification、
 * 不用 MyBatis、不用 JdbcTemplate、不用 nativeQuery。
 */
public interface WeighTicketRepository extends JpaRepository<WeighTicket, Long> {

    /** 按磅单号取 */
    WeighTicket findByTicketNo(String ticketNo);

    /**
     * 按磅单号取并加行级写锁（SELECT ... FOR UPDATE）。
     * 复称发起与结算确认在同一行锁上排队：先拿到锁的链路成功，后到的链路看到的已是
     * SETTLED 或 frozen=1，必然失败，从根上杜绝"钱已付出但复称仍待处理"。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM WeighTicket t WHERE t.ticketNo = :ticketNo")
    WeighTicket findByTicketNoForUpdate(@Param("ticketNo") String ticketNo);

    List<WeighTicket> findByStatusOrderByWeighTimeDesc(String status);

    /** 付款台选单：待结算且未被复称冻结（冻结中的磅单即使前端绕过来也由服务层再拦一次） */
    List<WeighTicket> findByStatusAndFrozenFlagFalseOrderByWeighTimeDesc(String status);

    /** 最近开的单子（列表页默认按时间倒序） */
    List<WeighTicket> findTop30ByOrderByWeighTimeDesc();

    /** 同一天内的单子数，用来生成当日流水号 */
    long countByStatDate(LocalDate statDate);

    long countByStatDateAndCustomerName(LocalDate statDate, String customerName);

    long countByStatus(String status);

    /**
     * 日结对账 · 按日期汇总：回收量、回收支出、出售收入、毛利、单量。
     * 收入侧用到「当日出售单价」，所以明细行要 JOIN 品类表。
     */
    @Query("SELECT new com.recycle.station.dto.DailySummaryRow("
            + "t.statDate, SUM(l.weight), SUM(l.subtotal), SUM(l.weight * c.sellPrice), "
            + "SUM(l.weight * c.sellPrice) - SUM(l.subtotal), COUNT(DISTINCT t.id)) "
            + "FROM WeighTicketLine l "
            + "JOIN l.ticket t "
            + "JOIN l.category c "
            + "WHERE t.status <> 'VOID' AND t.statDate BETWEEN :from AND :to "
            + "GROUP BY t.statDate "
            + "ORDER BY t.statDate DESC")
    List<DailySummaryRow> summaryByDate(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * 日结对账 · 客户供货 TOP 榜：按客户分组统计磅单张数、净重、金额（作废单不进榜）。
     */
    @Query("SELECT new com.recycle.station.dto.CustomerRankRow("
            + "t.customerName, t.customerType, COUNT(t), SUM(t.netWeight), SUM(t.totalAmount)) "
            + "FROM WeighTicket t "
            + "WHERE t.status <> 'VOID' AND t.statDate BETWEEN :from AND :to "
            + "GROUP BY t.customerName, t.customerType "
            + "ORDER BY SUM(t.netWeight) DESC")
    List<CustomerRankRow> customerTop(@Param("from") LocalDate from, @Param("to") LocalDate to);

    // ---------------- 复称闭环：当前有效口径聚合（模块六） ----------------

    /**
     * 按日期汇总的磅单级口径：净重 / 金额一律取 effective_*（当前有效版本），
     * 复称放行后报表自动跟随新版本，不再读被保留但已失效的原金额。
     */
    @Query("SELECT new com.recycle.station.dto.DailySummaryRow("
            + "t.statDate, SUM(t.effectiveNetWeight), SUM(t.effectiveTotalAmount), "
            + "COUNT(DISTINCT t.id)) "
            + "FROM WeighTicket t "
            + "WHERE t.status <> 'VOID' AND t.statDate BETWEEN :from AND :to "
            + "GROUP BY t.statDate")
    List<DailySummaryRow> effectiveSummaryByDate(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT new com.recycle.station.dto.CustomerRankRow("
            + "t.customerName, t.customerType, COUNT(t), SUM(t.effectiveNetWeight), SUM(t.effectiveTotalAmount)) "
            + "FROM WeighTicket t "
            + "WHERE t.status <> 'VOID' AND t.statDate BETWEEN :from AND :to "
            + "GROUP BY t.customerName, t.customerType "
            + "ORDER BY SUM(t.effectiveNetWeight) DESC")
    List<CustomerRankRow> effectiveCustomerTop(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
