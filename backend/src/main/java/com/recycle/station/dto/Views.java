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
                              String requiredLicenseClass, String requiredLicenseClassName,
                              boolean pendingDispatch, String pendingDispatchReason, Long lockVersion) {
    }

    // ---------------- 模块七：证照联锁 + 派车快照 ----------------

    public record DriverView(Long id, String driverName, String phone, String licenseClass,
                             String licenseClassName, String status, String statusName, String remark) {
    }

    /** 证照列表中的一个版本（含状态中文名、证件类型中文名、经办人/复核人） */
    public record CredentialDocView(Long id, String ownerType, Long ownerId, String ownerName, String docType,
                                    String docTypeName, Integer versionNo, String groupUuid, String docNumber,
                                    String licenseClass, String licenseClassName, String subjectDesc,
                                    LocalDate validFrom, LocalDate validUntil, String attachmentNote,
                                    String status, String statusName, String handler, String reviewer,
                                    String reason, LocalDateTime createdAt, LocalDateTime submittedAt,
                                    LocalDateTime reviewedAt, LocalDateTime closedAt, Long lockVersion,
                                    boolean expiredByToday) {
    }

    /** 待复核队列一项：待办动作 + 是否本人经办（本人不能复核） */
    public record ReviewQueueItem(CredentialDocView doc, String ownerType, Long ownerId, String ownerName,
                                  String docTypeName, String handler, LocalDateTime submittedAt,
                                  List<String> requiredTypes) {
    }

    /** 证照变化轨迹一条 */
    public record CredentialEventView(Long id, Long docId, String action, String actionName,
                                      String fromStatus, String fromStatusName, String toStatus, String toStatusName,
                                      String operator, String reason, LocalDateTime createdAt) {
    }

    /** 新旧版本差异中单个字段的对比 */
    public record DiffField(String field, String fieldName, String oldValue, String newValue, boolean changed) {
    }

    /** 一条版本链的全貌：全部版本 + 逐版差异 + 轨迹 */
    public record CredentialGroupView(String ownerType, Long ownerId, String ownerName, String docType,
                                      String docTypeName, String groupUuid,
                                      List<CredentialDocView> versions, List<CredentialEventView> events,
                                      List<Map<String, Object>> diffs) {
    }

    /** 派车资格快照中的一行（某次出车当时采用的某份证照版本） */
    public record SnapshotLineView(Long docId, String ownerType, String ownerName, String docType,
                                   String docTypeName, Integer docVersionNo, String docNumber,
                                   String licenseClass, String licenseClassName, String subjectDesc,
                                   LocalDate validFrom, LocalDate validUntil, String attachmentNote,
                                   String handler, String reviewer) {
    }

    /** 单次出车 + 派车资格快照 */
    public record TripView(Long id, String tripNo, Long vehicleId, String plateNo, Long driverId,
                           String driverName, String driverLicenseClass, String driverLicenseClassName,
                           String requiredLicenseClass, String requiredLicenseClassName,
                           LocalDateTime dispatchedAt, LocalDateTime plannedReturnAt,
                           LocalDateTime returnedAt, String status, String statusName,
                           boolean pendingDispatch, String pendingReason, String dispatcher, String remark,
                           List<SnapshotLineView> snapshots) {
    }

    /**
     * 车辆当前可派状态（车辆列表直接展示不可派原因）：
     * dispatchable=false 时 reasons 给出逐条人话 + 错误码（CRED_EXPIRED / LICENSE_CLASS_MISMATCH …）。
     */
    public record VehicleDispatchView(VehicleView vehicle, String requiredLicenseClass,
                                      String requiredLicenseClassName, boolean dispatchable,
                                      List<Map<String, Object>> reasons, Long openTripId,
                                      String openTripNo, String openDriverName,
                                      LocalDateTime plannedReturnAt, boolean pendingDispatch,
                                      String pendingReason, Long lockVersion) {
    }

    /** 派车试算结果：联锁全部通过时返回将固化的快照清单，供确认弹窗核对 */
    public record DispatchCheckView(boolean passable, List<Map<String, Object>> reasons,
                                    Long vehicleId, String plateNo, Long driverId, String driverName,
                                    String driverLicenseClass, String requiredLicenseClass,
                                    LocalDateTime plannedReturnAt, List<SnapshotLineView> wouldFreeze) {
    }

    /** 撤销/换版时受影响的在途车辆 */
    public record AffectedTripView(String tripNo, String plateNo, String driverName,
                                   LocalDateTime dispatchedAt, LocalDateTime plannedReturnAt,
                                   String docTypeName, Integer docVersionNo,
                                   boolean pendingDispatch, String pendingReason) {
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
