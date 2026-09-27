package com.recycle.station.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 回收站词典与业务规则参数表（品类单位、状态机、溢价系数、上下限等）。
 * 规则集中在此，便于出题时被要求调整或扩展。
 */
public final class StationDict {

    private StationDict() {
    }

    // ---------------- 计量单位 ----------------
    private static final Map<String, String> UNIT = Map.of(
            "KG", "公斤",
            "SET", "台");

    /** 过磅允许的计量单位 */
    public static final List<String> UNIT_ORDER = List.of("KG", "SET");

    // ---------------- 客户类型 ----------------
    private static final Map<String, String> CUSTOMER_TYPE = Map.of(
            "PERSONAL", "个人",
            "MERCHANT", "商户");

    // ---------------- 过磅单状态 ----------------
    private static final Map<String, String> TICKET_STATUS = Map.of(
            "PENDING", "待结算",
            "SETTLED", "已结算",
            "VOID", "作废");

    /** 过磅单状态流转表：key 为当前状态，value 为允许切到的状态 */
    private static final Map<String, List<String>> TICKET_NEXT = Map.of(
            "PENDING", List.of("SETTLED", "VOID"),
            "SETTLED", List.of("VOID"),
            "VOID", List.of());

    // ---------------- 车辆状态 ----------------
    private static final Map<String, String> VEHICLE_STATUS = Map.of(
            "IDLE", "待命",
            "ON_ROAD", "在途",
            "REPAIR", "维修");

    private static final List<String> VEHICLE_STATUS_ORDER = List.of("IDLE", "ON_ROAD", "REPAIR");

    // ---------------- 准驾车型（证照有效期联锁台） ----------------
    /** 准驾车型由低到高：高级别覆盖低级别（A1 覆盖全部货车口径） */
    private static final List<String> LICENSE_CLASS_ORDER = List.of("C1", "B2", "A2", "A1");

    private static final Map<String, String> LICENSE_CLASS_NAME = Map.of(
            "C1", "C1 小型货车",
            "B2", "B2 大型货车",
            "A2", "A2 牵引车",
            "A1", "A1 大型客车/重型");

    // ---------------- 证照类型 ----------------
    private static final Map<String, String> CREDENTIAL_TYPE = Map.of(
            "CRED_DRIVER_LICENSE", "驾驶证",
            "CRED_QUALIFICATION", "货运从业资格证",
            "CRED_ANNUAL_INSPECTION", "车辆年检",
            "CRED_INSURANCE", "机动车交强险",
            "CRED_TRANSPORT_PERMIT", "道路运输证");

    /** 司机必需证照（每次派车都必须在计划回场前持续有效） */
    public static final List<String> DRIVER_REQUIRED_DOCS =
            List.of("CRED_DRIVER_LICENSE", "CRED_QUALIFICATION");

    /** 车辆全部必需证照 */
    public static final List<String> VEHICLE_REQUIRED_DOCS =
            List.of("CRED_ANNUAL_INSPECTION", "CRED_INSURANCE", "CRED_TRANSPORT_PERMIT");

    // ---------------- 证照版本状态 ----------------
    private static final Map<String, String> CREDENTIAL_STATUS = Map.of(
            "DRAFT", "草稿",
            "PENDING_REVIEW", "待复核",
            "EFFECTIVE", "生效",
            "REJECTED", "驳回",
            "REVOKED", "撤销",
            "EXPIRED", "到期",
            "SUPERSEDED", "被新版本替代");

    /** 版本状态轨迹顺序（列表/轨迹展示） */
    public static final List<String> CREDENTIAL_STATUS_ORDER =
            List.of("DRAFT", "PENDING_REVIEW", "EFFECTIVE", "REJECTED", "REVOKED", "EXPIRED", "SUPERSEDED");

    /** 证照变化轨迹动作 → 中文 */
    private static final Map<String, String> CREDENTIAL_ACTION_NAME = Map.of(
            "CREATE", "录入草稿",
            "SUBMIT", "提交复核",
            "APPROVE", "复核生效",
            "REJECT", "驳回",
            "REVOKE", "撤销",
            "EXPIRE", "到期",
            "SUPERSEDE", "被新版本替代");

    // ---------------- 司机状态 ----------------
    private static final Map<String, String> DRIVER_STATUS = Map.of(
            "ACTIVE", "在岗",
            "RESTING", "休息",
            "RESIGNED", "离职");

    // ---------------- 出车状态 ----------------
    private static final Map<String, String> TRIP_STATUS = Map.of(
            "ON_ROAD", "在途",
            "RETURNED", "已回场");

    // ---------------- 结算方式 ----------------
    private static final Map<String, String> PAY_MODE = Map.of(
            "CASH", "现金",
            "TRANSFER", "转账",
            "DEDUCT", "抵扣");

    private static final List<String> PAY_MODE_ORDER = List.of("CASH", "TRANSFER", "DEDUCT");

    // ---------------- 复称单状态（模块六） ----------------
    private static final Map<String, String> REWEIGH_STATUS = Map.of(
            "INITIATED", "已发起·待补录",
            "AWAIT_RELEASE", "待现场放行",
            "AWAIT_REVIEW", "待复核",
            "RELEASED", "现场放行",
            "APPROVED", "复核通过",
            "REJECTED", "复核驳回");

    /** 复称环节动作 → 事件类型，轨迹时间线按它展示 */
    private static final Map<String, String> REWEIGH_EVENT_NAME = Map.of(
            "INITIATED", "发起复称",
            "SUPPLEMENTED", "补录复称数据",
            "RELEASED", "现场放行",
            "APPROVED", "复核通过",
            "REJECTED", "复核驳回",
            "SETTLEMENT_FROZEN", "结算冻结",
            "SETTLEMENT_RESUMED", "恢复结算");

    /** 复称单状态流转表：key 为当前状态，value 为允许切到的状态 */
    private static final Map<String, List<String>> REWEIGH_NEXT = Map.of(
            "INITIATED", List.of("AWAIT_RELEASE", "AWAIT_REVIEW"),
            "AWAIT_RELEASE", List.of("RELEASED", "AWAIT_REVIEW"),
            "AWAIT_REVIEW", List.of("APPROVED", "REJECTED"),
            "RELEASED", List.of(),
            "APPROVED", List.of(),
            "REJECTED", List.of());

    /** 未结束（结算仍冻结）的复称状态 */
    public static final List<String> REWEIGH_OPEN_STATUSES =
            List.of("INITIATED", "AWAIT_RELEASE", "AWAIT_REVIEW");

    /** 已经终审（放行 / 通过 / 驳回），不得再次改判 */
    public static final List<String> REWEIGH_CLOSED_STATUSES =
            List.of("RELEASED", "APPROVED", "REJECTED");

    // ---------------- 规则参数 ----------------
    /** 单个客户单日最多允许开几张过磅单 */
    public static final int MAX_TICKET_PER_CUSTOMER_DAY = 5;

    /** 一张过磅单最多允许几行明细 */
    public static final int MAX_LINES = 8;

    /** 明细重量上限（公斤/台），超出多半是磅秤读数填错了 */
    public static final BigDecimal MAX_LINE_WEIGHT = BigDecimal.valueOf(60000);

    /** 单价上限（元），防止调价时多敲一个 0 */
    public static final BigDecimal MAX_UNIT_PRICE = BigDecimal.valueOf(20000);

    /** 结算金额允许的抹零额度（元）：在这个范围内取整到元 */
    public static final BigDecimal ROUND_TOLERANCE = BigDecimal.valueOf(5);

    // ---------------- 复称规则参数（模块六） ----------------
    /** 现场放行的净重差异绝对值上限（公斤）：≤2kg 才允许走现场确认 */
    public static final BigDecimal REWEIGH_DIFF_WEIGHT_LIMIT = BigDecimal.valueOf(2);

    /** 现场放行的净重差异比例上限：≤原净重的千分之五 */
    public static final BigDecimal REWEIGH_DIFF_RATIO_LIMIT = BigDecimal.valueOf(0.005);

    /** 差异原因最大长度 */
    public static final int REWEIGH_REASON_MAX = 200;

    // ---------------- 取值方法 ----------------

    public static String unitName(String unit) {
        return unit == null ? "" : UNIT.getOrDefault(unit, unit);
    }

    public static boolean isValidUnit(String unit) {
        return unit != null && UNIT.containsKey(unit);
    }

    public static String customerTypeName(String type) {
        return type == null ? "" : CUSTOMER_TYPE.getOrDefault(type, type);
    }

    public static boolean isValidCustomerType(String type) {
        return type != null && CUSTOMER_TYPE.containsKey(type);
    }

    public static boolean isValidTicketStatus(String status) {
        return status != null && TICKET_STATUS.containsKey(status);
    }

    public static String ticketStatusName(String status) {
        return status == null ? "" : TICKET_STATUS.getOrDefault(status, status);
    }

    public static List<String> ticketNextStatuses(String status) {
        return TICKET_NEXT.getOrDefault(status, List.of());
    }

    public static boolean isValidVehicleStatus(String status) {
        return status != null && VEHICLE_STATUS.containsKey(status);
    }

    public static String vehicleStatusName(String status) {
        return status == null ? "" : VEHICLE_STATUS.getOrDefault(status, status);
    }

    public static List<String> vehicleStatusOrder() {
        return VEHICLE_STATUS_ORDER;
    }

    // ---------------- 准驾车型取值方法 ----------------

    public static boolean isValidLicenseClass(String licenseClass) {
        return licenseClass != null && LICENSE_CLASS_NAME.containsKey(licenseClass);
    }

    public static String licenseClassName(String licenseClass) {
        return licenseClass == null ? "" : LICENSE_CLASS_NAME.getOrDefault(licenseClass, licenseClass);
    }

    public static List<String> licenseClassOrder() {
        return LICENSE_CLASS_ORDER;
    }

    /**
     * 准驾覆盖：driverClass 的等级 ≥ requiredClass 才算覆盖（A2 可开 B2/C1 要求的车）。
     * 这是“司机准驾车型能覆盖该车辆”的唯一判定口径。
     */
    public static boolean licenseCovers(String driverClass, String requiredClass) {
        int driverRank = LICENSE_CLASS_ORDER.indexOf(driverClass);
        int requiredRank = LICENSE_CLASS_ORDER.indexOf(requiredClass);
        return driverRank >= 0 && requiredRank >= 0 && driverRank >= requiredRank;
    }

    /** 按载重推定车辆所需最低准驾车型：≤4.5 吨 C1，≤12 吨 B2，其余 A2 */
    public static String requiredLicenseByLoad(BigDecimal loadCapacity) {
        if (loadCapacity == null) {
            return "B2";
        }
        if (loadCapacity.compareTo(BigDecimal.valueOf(4.5)) <= 0) {
            return "C1";
        }
        if (loadCapacity.compareTo(BigDecimal.valueOf(12)) <= 0) {
            return "B2";
        }
        return "A2";
    }

    // ---------------- 证照取值方法 ----------------

    public static boolean isValidCredentialType(String docType) {
        return docType != null && CREDENTIAL_TYPE.containsKey(docType);
    }

    public static String credentialTypeName(String docType) {
        return docType == null ? "" : CREDENTIAL_TYPE.getOrDefault(docType, docType);
    }

    public static boolean isDriverCredential(String docType) {
        return DRIVER_REQUIRED_DOCS.contains(docType);
    }

    public static List<String> requiredCredentialTypes(String ownerType) {
        return "DRIVER".equals(ownerType) ? DRIVER_REQUIRED_DOCS : VEHICLE_REQUIRED_DOCS;
    }

    public static boolean isValidCredentialStatus(String status) {
        return status != null && CREDENTIAL_STATUS.containsKey(status);
    }

    public static String credentialStatusName(String status) {
        return status == null ? "" : CREDENTIAL_STATUS.getOrDefault(status, status);
    }

    public static String credentialActionName(String action) {
        return action == null ? "" : CREDENTIAL_ACTION_NAME.getOrDefault(action, action);
    }

    public static boolean isValidDriverStatus(String status) {
        return status != null && DRIVER_STATUS.containsKey(status);
    }

    public static String driverStatusName(String status) {
        return status == null ? "" : DRIVER_STATUS.getOrDefault(status, status);
    }

    public static String tripStatusName(String status) {
        return status == null ? "" : TRIP_STATUS.getOrDefault(status, status);
    }

    public static boolean isValidPayMode(String mode) {
        return mode != null && PAY_MODE.containsKey(mode);
    }

    public static String payModeName(String mode) {
        return mode == null ? "" : PAY_MODE.getOrDefault(mode, mode);
    }

    public static List<String> payModeOrder() {
        return PAY_MODE_ORDER;
    }

    // ---------------- 复称取值方法 ----------------

    public static boolean isValidReweighStatus(String status) {
        return status != null && REWEIGH_STATUS.containsKey(status);
    }

    public static String reweighStatusName(String status) {
        return status == null ? "" : REWEIGH_STATUS.getOrDefault(status, status);
    }

    public static String reweighEventName(String eventType) {
        return eventType == null ? "" : REWEIGH_EVENT_NAME.getOrDefault(eventType, eventType);
    }

    public static List<String> reweighNextStatuses(String status) {
        return REWEIGH_NEXT.getOrDefault(status, List.of());
    }

    public static boolean isReweighOpen(String status) {
        return status != null && REWEIGH_OPEN_STATUSES.contains(status);
    }

    public static boolean isReweighClosed(String status) {
        return status != null && REWEIGH_CLOSED_STATUSES.contains(status);
    }

    /**
     * 复称净重差异是否在"可由另一名当班人员现场放行"的双阈值内：
     * |差异| ≤ 2 公斤 且 |差异| ≤ 原净重 × 5‰。两个条件同时满足才可现场放行，
     * 任一超出都必须进待复核。
     */
    public static boolean diffWithinReleaseThreshold(BigDecimal originNet, BigDecimal diffNet) {
        BigDecimal abs = diffNet.abs();
        if (abs.compareTo(REWEIGH_DIFF_WEIGHT_LIMIT) > 0) {
            return false;
        }
        BigDecimal ratioCap = originNet.multiply(REWEIGH_DIFF_RATIO_LIMIT);
        return abs.compareTo(ratioCap) <= 0;
    }
}
