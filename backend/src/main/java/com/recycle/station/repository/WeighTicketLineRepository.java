package com.recycle.station.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.recycle.station.domain.WeighTicketLine;
import com.recycle.station.domain.WeighTicketLineId;
import com.recycle.station.dto.CategoryTotalRow;
import com.recycle.station.dto.DailyCategoryStats;

/**
 * 过磅单明细行。
 * <p>
 * 主键是 {@link WeighTicketLineId}（磅单号 + 行号）这个 {@code @IdClass} 复合主键，
 * 所以 {@code JpaRepository} 的第二个泛型参数也是它。
 */
public interface WeighTicketLineRepository extends JpaRepository<WeighTicketLine, WeighTicketLineId> {

    List<WeighTicketLine> findByTicketNoOrderByLineNoAsc(String ticketNo);

    /**
     * 日结对账主报表：按「日期 + 品类」聚合。
     * <ul>
     *   <li>{@code SUM(l.weight)} —— 回收量</li>
     *   <li>{@code SUM(l.subtotal)} —— 回收支出（站里付给客户的钱）</li>
     *   <li>{@code SUM(l.weight * c.sellPrice)} —— 出售收入（卖给下游再生资源厂的钱）</li>
     * </ul>
     */
    @Query("SELECT new com.recycle.station.dto.DailyCategoryStats("
            + "t.statDate, l.categoryNo, l.categoryName, l.unit, "
            + "SUM(l.weight), SUM(l.subtotal), SUM(l.weight * c.sellPrice), "
            + "SUM(l.weight * c.sellPrice) - SUM(l.subtotal), COUNT(l)) "
            + "FROM WeighTicketLine l "
            + "JOIN l.ticket t "
            + "JOIN l.category c "
            + "WHERE t.status <> 'VOID' AND t.statDate BETWEEN :from AND :to "
            + "GROUP BY t.statDate, l.categoryNo, l.categoryName, l.unit "
            + "ORDER BY t.statDate DESC, SUM(l.weight) DESC")
    List<DailyCategoryStats> statsByCategory(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 同一套聚合再按「品类」跨日期汇总，给页面画排行榜条形图 */
    @Query("SELECT new com.recycle.station.dto.CategoryTotalRow("
            + "l.categoryNo, l.categoryName, l.unit, "
            + "SUM(l.weight), SUM(l.subtotal), SUM(l.weight * c.sellPrice), "
            + "SUM(l.weight * c.sellPrice) - SUM(l.subtotal), COUNT(l)) "
            + "FROM WeighTicketLine l "
            + "JOIN l.ticket t "
            + "JOIN l.category c "
            + "WHERE t.status <> 'VOID' AND t.statDate BETWEEN :from AND :to "
            + "GROUP BY l.categoryNo, l.categoryName, l.unit "
            + "ORDER BY SUM(l.weight) DESC")
    List<CategoryTotalRow> statsByCategoryTotal(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * 复称闭环的 V1 口径：仍处于 V1（未被复称改版本）的磅单，其原始明细参与聚合。
     * active_version_no = 1 的磅单原始数据从未被覆盖，直接读 weigh_ticket_line 即可。
     */
    @Query("SELECT new com.recycle.station.dto.DailyCategoryStats("
            + "t.statDate, l.categoryNo, l.categoryName, l.unit, "
            + "SUM(l.weight), SUM(l.subtotal), SUM(l.weight * c.sellPrice), "
            + "SUM(l.weight * c.sellPrice) - SUM(l.subtotal), COUNT(l)) "
            + "FROM WeighTicketLine l "
            + "JOIN l.ticket t "
            + "JOIN l.category c "
            + "WHERE t.status <> 'VOID' AND t.activeVersionNo = 1 "
            + "AND t.statDate BETWEEN :from AND :to "
            + "GROUP BY t.statDate, l.categoryNo, l.categoryName, l.unit")
    List<DailyCategoryStats> v1StatsByCategory(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT new com.recycle.station.dto.CategoryTotalRow("
            + "l.categoryNo, l.categoryName, l.unit, "
            + "SUM(l.weight), SUM(l.subtotal), SUM(l.weight * c.sellPrice), "
            + "SUM(l.weight * c.sellPrice) - SUM(l.subtotal), COUNT(l)) "
            + "FROM WeighTicketLine l "
            + "JOIN l.ticket t "
            + "JOIN l.category c "
            + "WHERE t.status <> 'VOID' AND t.activeVersionNo = 1 "
            + "AND t.statDate BETWEEN :from AND :to "
            + "GROUP BY l.categoryNo, l.categoryName, l.unit")
    List<CategoryTotalRow> v1StatsByCategoryTotal(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
