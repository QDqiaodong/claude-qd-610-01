package com.recycle.station.common;

/**
 * 统一返回包装：成功 {ok:true, code:"OK", message, data:...}，
 * 失败 {ok:false, code:"错误码", message:"中文业务提示"}。
 * <p>
 * code 供前端区分失败后果（见 {@link BizException} 里的常量），message 直接给人看。
 */
public class ApiResponse<T> {

    public static final String CODE_OK = "OK";

    private boolean ok;
    private String code;
    private String message;
    private T data;

    public ApiResponse() {
    }

    public ApiResponse(boolean ok, String code, String message, T data) {
        this.ok = ok;
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, CODE_OK, "OK", data);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, CODE_OK, message, data);
    }

    public static <T> ApiResponse<T> fail(String code, String message) {
        return new ApiResponse<>(false, code, message, null);
    }

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
