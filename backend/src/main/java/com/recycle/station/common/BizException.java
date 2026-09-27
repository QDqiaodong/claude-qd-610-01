package com.recycle.station.common;

/**
 * 业务异常：由业务规则主动抛出，统一异常处理会转成 {ok:false, code:"...", message:"..."}。
 * <p>
 * 复称闭环要求把"不同后果"用不同错误码区分开，前端按 code 决定刷新 / 提示 / 关弹窗，
 * 不能再用同一句"操作失败"掩盖磅单状态、并发抢占、越权审核等完全不同的情况。
 */
public class BizException extends RuntimeException {

    /** 磅单当前状态不允许该操作（已结算 / 已作废 / 非待结算） */
    public static final String TICKET_STATUS_DENIED = "TICKET_STATUS_DENIED";

    /** 同一磅单已有一条未结束的复称 */
    public static final String REWEIGH_IN_PROGRESS = "REWEIGH_IN_PROGRESS";

    /** 发起人不能审核 / 放行自己的复称记录 */
    public static final String SELF_REVIEW_FORBIDDEN = "SELF_REVIEW_FORBIDDEN";

    /** 数据版本已变化（旧页面 / 乐观锁冲突，必须刷新后再操作） */
    public static final String VERSION_CONFLICT = "VERSION_CONFLICT";

    /** 结算已经抢先完成：复称发起慢了一步，钱已付出 */
    public static final String SETTLEMENT_RACED = "SETTLEMENT_RACED";

    // ---------------- 证照有效期联锁台（模块七） ----------------

    /** 证照资料仍待复核（还没有生效版本，不能作为派车依据） */
    public static final String CRED_PENDING_REVIEW = "CRED_PENDING_REVIEW";

    /** 证照已过期（当前日期已超过有效期） */
    public static final String CRED_EXPIRED = "CRED_EXPIRED";

    /** 司机准驾车型不匹配（驾驶证等级覆盖不了车辆要求） */
    public static final String LICENSE_CLASS_MISMATCH = "LICENSE_CLASS_MISMATCH";

    /** 计划回场时间晚于证照有效期（证照在途期间会断档） */
    public static final String RETURN_BEYOND_VALID_UNTIL = "RETURN_BEYOND_VALID_UNTIL";

    /** 车辆已有未结束出车（在途未回场，不能重复派车） */
    public static final String TRIP_ALREADY_OPEN = "TRIP_ALREADY_OPEN";

    /** 车辆维修中，不能派车 */
    public static final String VEHICLE_UNDER_REPAIR = "VEHICLE_UNDER_REPAIR";

    /** 经办人不能复核自己录入的资料 */
    public static final String SELF_CRED_REVIEW_FORBIDDEN = "SELF_CRED_REVIEW_FORBIDDEN";

    /** 通用业务校验（不与并发 / 权限相关的普通校验） */
    public static final String BIZ_INVALID = "BIZ_INVALID";

    private final String code;

    public BizException(String message) {
        this(BIZ_INVALID, message);
    }

    public BizException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
