package com.recycle.station.common;

import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务校验失败：409 表示"请求与资源当前状态冲突"（复称/结算并发场景大多是这个语义），
     * body 里带明确 code，前端按 code 分别处理。
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(e.getCode(), e.getMessage()));
    }

    /**
     * 乐观锁冲突：付款人员开着旧页面提交、或两条审核链路同时改判，
     * 统一归类为"数据版本已变化"，提示刷新后重来。
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimistic(ObjectOptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(BizException.VERSION_CONFLICT,
                        "数据版本已变化：页面上的磅单 / 复称状态已被其他人更新，请刷新后再操作"));
    }

    /**
     * 参数注解校验失败：400 + 字段名与提示
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(BizException.BIZ_INVALID,
                        message.isEmpty() ? "请求参数校验失败" : message));
    }

    /**
     * 请求体格式错误：400
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(BizException.BIZ_INVALID, "请求体格式有误，请检查 JSON 字段类型"));
    }

    /**
     * 唯一约束冲突：复称发起并发时由 uk_open_reweigh 兜底的那条会走到这里，
     * 转成"已有复称处理中"，不抛数据库外键黑话。其余约束给通用提示。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleIntegrity(DataIntegrityViolationException e) {
        String text = String.valueOf(e.getMostSpecificCause().getMessage());
        if (text.contains("uk_open_reweigh")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.fail(BizException.REWEIGH_IN_PROGRESS,
                            "已有复称处理中：该磅单存在一条未结束的复称记录，请在复称复核台处理完后再发起"));
        }
        if (text.contains("uk_reweigh_idempotent")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.fail(BizException.REWEIGH_IN_PROGRESS,
                            "复称正在处理中，请勿重复发起（相同请求已受理）"));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(BizException.BIZ_INVALID, "存在唯一性约束冲突，请检查编号是否重复"));
    }

    /**
     * 兜底：500
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(BizException.BIZ_INVALID, "服务异常：" + detail));
    }
}
