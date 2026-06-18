package com.kylinops.common;

import lombok.Getter;

/**
 * 业务异常 — 用于在 Service / Agent 层抛出受控的、可处理的异常。
 * <p>
 * 由 {@link GlobalExceptionHandler} 统一捕获并转换为 {@link ApiResponse} 返回。
 * </p>
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 业务错误码 */
    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * 快速创建 400 参数错误
     */
    public static BusinessException badRequest(String message) {
        return new BusinessException(400, message);
    }

    /**
     * 快速创建 403 无权限
     */
    public static BusinessException forbidden(String message) {
        return new BusinessException(403, message);
    }

    /**
     * 快速创建 404 未找到
     */
    public static BusinessException notFound(String message) {
        return new BusinessException(404, message);
    }

    /**
     * 快速创建 409 冲突
     */
    public static BusinessException conflict(String message) {
        return new BusinessException(409, message);
    }
}
