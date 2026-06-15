package com.kylinops.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

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

    // ==================== 辅助：JSON 序列化错误响应 ====================

    /**
     * 生成 12 字符追踪 ID（无分隔符小写 hex，与 AuditLog 避免含特殊字符保持一致）。
     */
    public static String generateTraceId() {
        return UUID.randomUUID().toString().replaceAll("-", "").substring(0, 12);
    }

    /**
     * 将错误响应写入 {@link HttpServletResponse}，含 traceId 和 UTF-8 JSON Content-Type。
     * <p>
     * 替代多处手写 {@code response.setStatus() + response.setContentType() + objectMapper.writeValue()} 的重复模式。
     * </p>
     *
     * @param response     HTTP 响应
     * @param httpStatus   HTTP 状态码
     * @param message      错误消息
     * @param objectMapper Jackson ObjectMapper（null 时跳过序列化，仅设 status/headers）
     * @param <T>          响应 data 类型
     * @throws IOException 如果输出流写入失败
     */
    public static <T> void writeJsonError(HttpServletResponse response, int httpStatus,
                                          String message, ObjectMapper objectMapper) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        ApiResponse<T> body = error(httpStatus, message);
        body.traceId(generateTraceId());
        if (objectMapper != null) {
            objectMapper.writeValue(response.getOutputStream(), body);
        }
    }
}
