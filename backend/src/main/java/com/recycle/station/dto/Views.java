package com.recycle.station.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 出参视图。全是不可变 record，只读不接输入，避免手抖改回实体。
 */
public final class Views {

    private Views() {
    }

    public record CategoryView(Long id, String categoryNo, String categoryName, String unit, String unitName,
                               BigDecimal buyPrice, BigDecimal sellPrice, BigDecimal margin, Boolean stored,
                               String remark, LocalDateTime updatedAt) {
    }

    public record LineView(Integer lineNo, String categoryNo, String categoryName, String unit, String unitName,
                           BigDecimal weight, BigDecimal unitPrice, BigDecimal subtotal) {
    }

    public record TicketView(Long id, String ticketNo, String customerName, String customerType,
                             String customerTypeName, LocalDateTime weighTime, LocalDate statDate, String plateNo,
                             BigDecimal grossWeight, BigDecimal tareWeight, BigDecimal netWeight,
                             BigDecimal totalAmount, String status, String statusName, String remark,
                             LocalDateTime createdAt, List<LineView> lines, List<Map<String, String>> nextStatuses,
                             String settleNo,
                             boolean frozen, String frozenReason, String frozenReweighNo,
                             Integer activeVersionNo, BigDecimal effectiveNetWeight,
                             BigDecimal effectiveTotalAmount) {
    }

    /**
     * 磅单视图（含复称闭环口径）：
     * 原始三项重量/金额来自建档快照；effective* 是当前结算口径（可能来自复称后的新版本）；
     * frozen* 告诉前端为什么不能结算、当前责任人是谁。
     */
    public record TicketReweighView(String ticketNo, String customerName, String customerTypeName,
                                    String plateNo, String status, String statusName,
                                    BigDecimal grossWeight, BigDecimal tareWeight, BigDecimal netWeight,
                                    BigDecimal totalAmount,
                                    Integer activeVersionNo, BigDecimal effectiveNetWeight,
                                    BigDecimal effectiveTotalAmount,
                                    boolean frozen, String frozenReason, String frozenReweighNo,
                                    boolean openReweigh, String openReweighNo, String openReweighStatus,
                                    String openReweighStatusName, String currentOwner, Long lockVersion,
                                    List<LineView> lines, List<TicketVersionView> versions) {
    }

    /** 磅单重量版本（版本轨迹里的一条） */
    public record TicketVersionView(Integer versionNo, String sourceReweighNo,
                                    BigDecimal grossWeight, BigDecimal tareWeight, BigDecimal netWeight,
                                    BigDecimal totalAmount, String createdBy, LocalDateTime createdAt,
                                    List<LineView> lines) {
    }

    /** 复称明细行视图：原数量/单价快照 + 重分配数量与小计 */
    public record ReweighLineView(Integer lineNo, String categoryNo, String categoryName, String unit,
                                  String unitName, BigDecimal originWeight, BigDecimal unitPrice,
                                  BigDecimal newWeight, BigDecimal subtotal) {
    }

    /** 复称状态轨迹里的一条（append-only） */
    public record ReweighEventView(Long id, String eventType, String eventName, String fromStatus,
                                   String fromStatusName, String toStatus, String toStatusName,
                                   String operator, String note, LocalDateTime createdAt) {
    }

    /** 复称单视图：发起 / 补录 / 放行 / 复核全链路信息 */
    public record ReweighView(Long id, String reweighNo, String ticketNo, String customerName,
                              String customerTypeName, String plateNo,
                              BigDecimal originGrossWeight, BigDecimal originTareWeight,
                              BigDecimal originNetWeight, BigDecimal originTotalAmount,
                              BigDecimal reGrossWeight, BigDecimal reTareWeight, BigDecimal reNetWeight,
                              BigDecimal diffNetWeight, Boolean diffWithinThreshold, String diffReason,
                              String status, String statusName,
                              String initiator, LocalDateTime initiatedAt,
                              LocalDateTime supplementedAt, String releaser, LocalDateTime releasedAt,
                              String reviewer, LocalDateTime reviewedAt, String reviewResult,
                              String rejectReason, Integer adoptedVersionNo, boolean open,
                              String currentOwner, String currentOwnerRole,
                              boolean canAct, String nextAction, Long lockVersion,
                              List<ReweighLineView> lines, List<ReweighEventView> events) {
    }

    public record VehicleView(Long id, String plateNo, String driverName, String phone, BigDecimal loadCapacity,
                              String status, String statusName, LocalDate tripDate, Integer todayTrips,
                              String remark, List<Map<String, String>> statusOptions,
                              Long defaultDriverId, String requiredLicenseClass, String requiredLicenseClassName,
                              boolean dispatchable, List<Map<String, Object>> blockReasons,
                              Long openTripId, String openTripStatus, String openTripStatusName,
                              Long lockVersion) {
    }

    // ---------------- 模块七：证照联锁台 / 派车资格快照 ----------------

    /** 司机视图 */
    public record DriverView(Long id, String driverNo, String driverName, String phone,
                             String status, String statusName, Long lockVersion) {
    }

    /** 证照轨迹 */
    public record CertEventView(Long id, Long documentId, String eventType, String eventName,
                                String fromStatus, String fromStatusName,
                                String toStatus, String toStatusName,
                                String operator, String note, LocalDateTime createdAt) {
    }

    /** 资料版本视图 */
    public record CertDocumentView(Long id, Long seriesId, Integer versionNo,
                                   String subjectType, String subjectTypeName,
                                   Long subjectId, String subjectName,
                                   String certType, String certTypeName,
                                   String certNo, String licenseClass, String licenseClassName,
                                   LocalDate validFrom, LocalDate validTo, String attachmentNote,
                                   String operator, String reviewer,
                                   String status, String statusName,
                                   String rejectReason, String revokeReason,
                                   LocalDateTime reviewedAt, LocalDateTime revokedAt, LocalDateTime expiredAt,
                                   LocalDateTime createdAt, LocalDateTime updatedAt, Long lockVersion,
                                   boolean currentEffective, List<CertEventView> events) {
    }

    /** 证照版本链视图（证照列表一行 = 一条链） */
    public record CertSeriesView(Long id, String seriesNo,
                                 String subjectType, String subjectTypeName,
                                 Long subjectId, String subjectName,
                                 String certType, String certTypeName,
                                 Long currentDocumentId, Integer nextVersionNo,
                                 boolean hasEffective, CertDocumentView current,
                                 CertDocumentView pending,
                                 List<CertDocumentView> documents,
                                 List<Map<String, Object>> affectedTrips) {
    }

    /** 待复核队列项（资料版本 + 主体与证照信息冗余给队列直接展示） */
    public record CertReviewQueueItem(Long documentId, Long seriesId, Integer versionNo,
                                      String subjectType, String subjectTypeName,
                                      String subjectName, String certType, String certTypeName,
                                      String certNo, String licenseClass, String licenseClassName,
                                      LocalDate validFrom, LocalDate validTo, String attachmentNote,
                                      String operator, LocalDateTime createdAt, LocalDateTime updatedAt,
                                      Long lockVersion, boolean selfOperated) {
    }

    /** 派车资格快照明细 */
    public record TripCertView(Long id, Long seriesId, Long documentId,
                               String subjectType, String subjectTypeName,
                               String subjectName, String certType, String certTypeName,
                               String certNo, String licenseClass, String licenseClassName,
                               LocalDate validFrom, LocalDate validTo,
                               Integer versionNo, String status, String statusName) {
    }

    /** 单次出车视图（含派车资格快照） */
    public record DispatchTripView(Long id, String tripNo,
                                   Long vehicleId, String plateNo,
                                   Long driverId, String driverName,
                                   String dispatchOperator,
                                   LocalDateTime plannedReturnAt, LocalDateTime dispatchedAt,
                                   LocalDateTime returnedAt, String status, String statusName,
                                   String disposalReason, String dispositionNote, LocalDateTime dispositionAt,
                                   Long lockVersion, List<TripCertView> certs) {
    }

    /** 派车预核对结果（不落数据）：通过时给出将要固化的资格快照清单 */
    public record DispatchCheckView(boolean pass, List<Map<String, Object>> blockReasons,
                                    Long vehicleId, String plateNo, Long driverId, String driverName,
                                    String requiredLicenseClass, String driverLicenseClass,
                                    LocalDateTime plannedReturnAt, List<TripCertView> snapshot) {
    }

    public record SettlementView(Long id, String settleNo, String ticketNo, String customerName, String payMode,
                                 String payModeName, BigDecimal payableAmount, BigDecimal paidAmount,
                                 BigDecimal discount, Integer amountVersion, LocalDateTime settleTime,
                                 String operator, String remark) {
    }

    /** 首页顶部那排指标卡片 */
    public record OverviewView(LocalDate today, long todayTicketCount, BigDecimal todayWeight,
                               BigDecimal todayBuyAmount, BigDecimal todaySellAmount, BigDecimal todayGrossProfit,
                               long vehicleTotal, long vehicleOnRoad, long pendingTicketCount,
                               BigDecimal todaySettledAmount, List<Map<String, Object>> unitBreakdown) {
    }
}
