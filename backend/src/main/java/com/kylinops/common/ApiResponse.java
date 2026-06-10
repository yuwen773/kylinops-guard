package com.kylinops.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;

/**
 * 统一 API 响应封装
 * <p>
 * 所有 REST 接口统一使用此响应格式，确保前端可一致解析。
 * 成功时使用 {@link #success(Object)}，失败时使用 {@link #error(int, String)}。
 * </p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    /** 状态码：200 成功，其它为错误码 */
    private int code;

    /** 提示消息 */
    private String message;

    /** 响应数据 */
    private T data;

    /** 服务器时间戳（毫秒） */
    private long timestamp;

    /** 请求追踪 ID（可选，后续与 AuditLog 关联） */
    private String traceId;

    private ApiResponse() {
        this.timestamp = Instant.now().toEpochMilli();
    }

    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.code = 200;
        response.message = "success";
        response.data = data;
        return response;
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.code = 200;
        response.message = message;
        response.data = data;
        return response;
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.code = code;
        response.message = message;
        return response;
    }

    public static <T> ApiResponse<T> error(int code, String message, T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.code = code;
        response.message = message;
        response.data = data;
        return response;
    }

    public ApiResponse<T> traceId(String traceId) {
        this.traceId = traceId;
        return this;
    }
}
