package com.recycle.station.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.recycle.station.domain.WeighTicketVersionLine;
import com.recycle.station.dto.CategoryTotalRow;
import com.recycle.station.dto.DailyCategoryStats;

public interface WeighTicketVersionLineRepository extends JpaRepository<WeighTicketVersionLine, Long> {

    List<WeighTicketVersionLine> findByTicketNoAndVersionNoOrderByLineNoAsc(String ticketNo, Integer versionNo);

    /**
     * 日结报表的 V2+（复称生效版本）口径，按「日期 + 品类」聚合。
     * 只取每磅单 active_version_no 指向的那条版本明细：用 JOIN 磅单 + 版本号相等过滤。
     */
    @Query("SELECT new com.recycle.station.dto.DailyCategoryStats("
            + "t.statDate, vl.categoryNo, vl.categoryName, vl.unit, "
            + "SUM(vl.weight), SUM(vl.subtotal), SUM(vl.weight * c.sellPrice), "
            + "SUM(vl.weight * c.sellPrice) - SUM(vl.subtotal), COUNT(vl)) "
            + "FROM WeighTicketVersionLine vl "
            + "JOIN WeighTicket t ON t.ticketNo = vl.ticketNo AND t.activeVersionNo = vl.versionNo "
            + "JOIN RecycleCategory c ON c.categoryNo = vl.categoryNo "
            + "WHERE t.status <> 'VOID' AND t.activeVersionNo > 1 "
            + "AND t.statDate BETWEEN :from AND :to "
            + "GROUP BY t.statDate, vl.categoryNo, vl.categoryName, vl.unit")
    List<DailyCategoryStats> activeStatsByCategory(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT new com.recycle.station.dto.CategoryTotalRow("
            + "vl.categoryNo, vl.categoryName, vl.unit, "
            + "SUM(vl.weight), SUM(vl.subtotal), SUM(vl.weight * c.sellPrice), "
            + "SUM(vl.weight * c.sellPrice) - SUM(vl.subtotal), COUNT(vl)) "
            + "FROM WeighTicketVersionLine vl "
            + "JOIN WeighTicket t ON t.ticketNo = vl.ticketNo AND t.activeVersionNo = vl.versionNo "
            + "JOIN RecycleCategory c ON c.categoryNo = vl.categoryNo "
            + "WHERE t.status <> 'VOID' AND t.activeVersionNo > 1 "
            + "AND t.statDate BETWEEN :from AND :to "
            + "GROUP BY vl.categoryNo, vl.categoryName, vl.unit")
    List<CategoryTotalRow> activeStatsByCategoryTotal(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
