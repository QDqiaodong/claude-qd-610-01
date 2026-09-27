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

    // ---------------- 证照联锁台（模块七） ----------------

    /** 资料版本已变化：旧页面 / 网络超时重试 / 两人分别处理新旧版本，复核依据已不是提交时那版 */
    public static final String CERT_VERSION_CHANGED = "CERT_VERSION_CHANGED";

    /** 资料仍待复核：当前没有生效版本，只有草稿或待复核版本 */
    public static final String CERT_PENDING_REVIEW = "CERT_PENDING_REVIEW";

    /** 证照已过期（今天已超出有效期止期，到期状态） */
    public static final String CERT_EXPIRED = "CERT_EXPIRED";

    /** 准驾车型不匹配：司机驾驶证准驾车型覆盖不了该车辆 */
    public static final String LICENSE_CLASS_MISMATCH = "LICENSE_CLASS_MISMATCH";

    /** 计划回场晚于有效期：某份必需证照在计划回场时间之前就到期 */
    public static final String RETURN_BEYOND_VALIDITY = "RETURN_BEYOND_VALIDITY";

    /** 车辆已有未结束出车（在途未回场，或在途车被标记待调度处置） */
    public static final String TRIP_OPEN_EXISTS = "TRIP_OPEN_EXISTS";

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
