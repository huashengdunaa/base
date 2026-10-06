package com.demo.usermanage.common;

/**
 * 业务异常：用户不存在、用户名重复等可预期的业务错误。
 * 由 GlobalExceptionHandler 统一转换为标准响应体。
 */
public class BusinessException extends RuntimeException {

    /** 约定错误码：404 资源不存在，400 请求参数/业务规则冲突 */
    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
