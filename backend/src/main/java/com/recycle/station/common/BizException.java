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
