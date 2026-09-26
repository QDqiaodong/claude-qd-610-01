package com.recycle.station.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.recycle.station.domain.StationDict;
import com.recycle.station.dto.CategoryTotalRow;
import com.recycle.station.dto.CustomerRankRow;
import com.recycle.station.dto.DailyCategoryStats;
import com.recycle.station.dto.DailySummaryRow;
import com.recycle.station.dto.Views;
import com.recycle.station.repository.VehicleRepository;
import com.recycle.station.repository.WeighTicketLineRepository;
import com.recycle.station.repository.WeighTicketRepository;
import com.recycle.station.repository.WeighTicketVersionLineRepository;

/**
 * 模块五：日结对账报表。
 * <p>
 * 复称闭环接入后，所有统计都按"当前有效版本口径"合并两部分：
 * active_version_no=1 的磅单读原始 weigh_ticket_line（原始数据从未覆盖），
 * active_version_no&gt;1 的磅单读 weigh_ticket_version_line 中被指针指向的版本明细。
 * 这样复称放行后报表、首页与结算金额永远是同一版数字。
 */
@Service
public class StatsService {

    private final WeighTicketRepository ticketRepository;
    private final WeighTicketLineRepository lineRepository;
    private final WeighTicketVersionLineRepository versionLineRepository;
    private final VehicleRepository vehicleRepository;
    private final SettlementService settlementService;

    public StatsService(WeighTicketRepository ticketRepository,
                        WeighTicketLineRepository lineRepository,
                        WeighTicketVersionLineRepository versionLineRepository,
                        VehicleRepository vehicleRepository,
                        SettlementService settlementService) {
        this.ticketRepository = ticketRepository;
        this.lineRepository = lineRepository;
        this.versionLineRepository = versionLineRepository;
        this.vehicleRepository = vehicleRepository;
        this.settlementService = settlementService;
    }

    /** 首页顶部那排指标卡片 */
    @Transactional(readOnly = true)
    public Views.OverviewView overview() {
        LocalDate today = LocalDate.now();
        String from = today.toString();
        List<DailySummaryRow> rows = dailySummary(from, from);
        BigDecimal weight = BigDecimal.ZERO;
        BigDecimal buy = BigDecimal.ZERO;
        BigDecimal sell = BigDecimal.ZERO;
        BigDecimal profit = BigDecimal.ZERO;
        long ticketCount = 0L;
        if (!rows.isEmpty()) {
            DailySummaryRow row = rows.get(0);
            weight = nz(row.getTotalWeight());
            buy = nz(row.getBuyAmount());
            sell = nz(row.getSellAmount());
            profit = nz(row.getGrossProfit());
            ticketCount = row.getTicketCount() == null ? 0L : row.getTicketCount();
        }
        // 按计量单位拆一小份，给"今日回收量"这张卡片的副标题显示（有效版本口径）
        List<Map<String, Object>> unitBreakdown = new ArrayList<>();
        Map<String, BigDecimal> byUnit = new LinkedHashMap<>();
        for (CategoryTotalRow row : categoryTotal(from, from)) {
            byUnit.merge(row.getUnit() == null ? "KG" : row.getUnit(), nz(row.getTotalWeight()), BigDecimal::add);
        }
        byUnit.forEach((unit, amount) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("unit", unit);
            item.put("unitName", StationDict.unitName(unit));
            item.put("weight", amount.setScale(2, RoundingMode.HALF_UP));
            unitBreakdown.add(item);
        });
        long vehicleOnRoad = vehicleRepository.countByStatus("ON_ROAD");
        return new Views.OverviewView(today, ticketCount, scale(weight), scale(buy), scale(sell), scale(profit),
                vehicleRepository.count(), vehicleOnRoad, ticketRepository.countByStatus("PENDING"),
                settlementService.todaySettledAmount(), unitBreakdown);
    }

    /** 日结对账主报表：按日期 + 品类聚合（V1 原始明细 + V2+ 有效版本明细） */
    @Transactional(readOnly = true)
    public List<DailyCategoryStats> dailyCategory(String from, String to) {
        LocalDate f = LocalDate.parse(from);
        LocalDate t = LocalDate.parse(to);
        List<DailyCategoryStats> all = new ArrayList<>();
        all.addAll(lineRepository.v1StatsByCategory(f, t));
        all.addAll(versionLineRepository.activeStatsByCategory(f, t));

        // 同日期同品类合并（V1 与 V2+ 可能落在同一格）
        Map<String, DailyCategoryStats> merged = new LinkedHashMap<>();
        for (DailyCategoryStats row : all) {
            String key = row.getStatDate() + "|" + row.getCategoryNo();
            DailyCategoryStats hit = merged.get(key);
            if (hit == null) {
                merged.put(key, copy(row));
            } else {
                hit.setTotalWeight(nz(hit.getTotalWeight()).add(nz(row.getTotalWeight())));
                hit.setBuyAmount(nz(hit.getBuyAmount()).add(nz(row.getBuyAmount())));
                hit.setSellAmount(nz(hit.getSellAmount()).add(nz(row.getSellAmount())));
                hit.setGrossProfit(nz(hit.getGrossProfit()).add(nz(row.getGrossProfit())));
                hit.setLineCount(nz(hit.getLineCount()) + nz(row.getLineCount()));
            }
        }
        List<DailyCategoryStats> rows = new ArrayList<>(merged.values());
        rows.sort(Comparator.comparing(DailyCategoryStats::getStatDate).reversed()
                .thenComparing(r -> nz(r.getTotalWeight()).negate()));
        return rows;
    }

    /** 同一区间的品类汇总（横条图数据源）：V1 + V2+ 合并后按回收量倒序 */
    @Transactional(readOnly = true)
    public List<CategoryTotalRow> categoryTotal(String from, String to) {
        LocalDate f = LocalDate.parse(from);
        LocalDate t = LocalDate.parse(to);
        List<CategoryTotalRow> all = new ArrayList<>();
        all.addAll(lineRepository.v1StatsByCategoryTotal(f, t));
        all.addAll(versionLineRepository.activeStatsByCategoryTotal(f, t));

        Map<String, CategoryTotalRow> merged = new LinkedHashMap<>();
        for (CategoryTotalRow row : all) {
            CategoryTotalRow hit = merged.get(row.getCategoryNo());
            if (hit == null) {
                CategoryTotalRow copy = new CategoryTotalRow(row.getCategoryNo(), row.getCategoryName(),
                        row.getUnit(), nz(row.getTotalWeight()), nz(row.getBuyAmount()), nz(row.getSellAmount()),
                        nz(row.getGrossProfit()), nz(row.getLineCount()));
                merged.put(row.getCategoryNo(), copy);
            } else {
                hit.setTotalWeight(nz(hit.getTotalWeight()).add(nz(row.getTotalWeight())));
                hit.setBuyAmount(nz(hit.getBuyAmount()).add(nz(row.getBuyAmount())));
                hit.setSellAmount(nz(hit.getSellAmount()).add(nz(row.getSellAmount())));
                hit.setGrossProfit(nz(hit.getGrossProfit()).add(nz(row.getGrossProfit())));
                hit.setLineCount(nz(hit.getLineCount()) + nz(row.getLineCount()));
            }
        }
        List<CategoryTotalRow> rows = new ArrayList<>(merged.values());
        rows.sort((a, b) -> nz(b.getTotalWeight()).compareTo(nz(a.getTotalWeight())));
        return rows;
    }

    /** 按日期汇总：回收量 / 支出 / 收入 / 毛利（金额走磅单有效口径，收入与毛利从品类聚合累计） */
    @Transactional(readOnly = true)
    public List<DailySummaryRow> dailySummary(String from, String to) {
        LocalDate f = LocalDate.parse(from);
        LocalDate t = LocalDate.parse(to);

        // 重量、回收支出、单量：磅单级 effective_* 口径
        Map<LocalDate, DailySummaryRow> byDate = new LinkedHashMap<>();
        for (DailySummaryRow row : ticketRepository.effectiveSummaryByDate(f, t)) {
            DailySummaryRow base = new DailySummaryRow(row.getStatDate(), nz(row.getTotalWeight()),
                    nz(row.getBuyAmount()), BigDecimal.ZERO, BigDecimal.ZERO,
                    row.getTicketCount() == null ? 0L : row.getTicketCount());
            byDate.put(row.getStatDate(), base);
        }
        // 出售收入 / 毛利：品类级聚合（售价只能 join 品类表现价口径）按日期累加
        for (DailyCategoryStats stat : dailyCategory(from, to)) {
            DailySummaryRow day = byDate.computeIfAbsent(stat.getStatDate(),
                    d -> new DailySummaryRow(d, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                            BigDecimal.ZERO, 0L));
            day.setSellAmount(nz(day.getSellAmount()).add(nz(stat.getSellAmount())));
            day.setGrossProfit(nz(day.getGrossProfit()).add(nz(stat.getGrossProfit())));
        }
        List<DailySummaryRow> rows = new ArrayList<>(byDate.values());
        rows.sort((a, b) -> b.getStatDate().compareTo(a.getStatDate()));
        return rows;
    }

    /** 客户供货 TOP 榜（有效重量 / 有效金额口径） */
    @Transactional(readOnly = true)
    public List<CustomerRankRow> customerTop(String from, String to, Integer limit) {
        List<CustomerRankRow> rows = ticketRepository.effectiveCustomerTop(
                LocalDate.parse(from), LocalDate.parse(to));
        int size = limit == null || limit <= 0 ? rows.size() : Math.min(limit, rows.size());
        return rows.subList(0, size);
    }

    /**
     * 报表页一屏用的打包数据：明细表 + 日期汇总 + 品类横条 + 客户 TOP。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> board(String from, String to, Integer limit) {
        Map<String, Object> board = new LinkedHashMap<>();
        board.put("from", from);
        board.put("to", to);
        board.put("rows", dailyCategory(from, to));
        board.put("daily", dailySummary(from, to));
        board.put("categories", categoryTotal(from, to));
        board.put("customers", customerTop(from, to, limit));
        return board;
    }

    private DailyCategoryStats copy(DailyCategoryStats row) {
        return new DailyCategoryStats(row.getStatDate(), row.getCategoryNo(), row.getCategoryName(),
                row.getUnit(), nz(row.getTotalWeight()), nz(row.getBuyAmount()), nz(row.getSellAmount()),
                nz(row.getGrossProfit()), nz(row.getLineCount()));
    }

    private long nz(Long value) {
        return value == null ? 0L : value;
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal scale(BigDecimal value) {
        return nz(value).setScale(2, RoundingMode.HALF_UP);
    }
}
