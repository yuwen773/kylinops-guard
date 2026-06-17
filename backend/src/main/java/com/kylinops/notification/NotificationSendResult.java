package com.kylinops.notification;

import lombok.Builder;
import lombok.Data;

/**
 * 通知发送结果 — Channel.send() 返回值。
 */
@Data
@Builder
public class NotificationSendResult {
    /** 是否成功(HTTP 2xx) */
    private boolean success;
    /** HTTP 响应码 */
    private Integer responseCode;
    /** 响应体(截断到 1KB) */
    private String responseBody;
    /** 错误信息(失败时填) */
    private String errorMessage;

    public static NotificationSendResult ok(int code, String body) {
        return NotificationSendResult.builder()
                .success(true)
                .responseCode(code)
                .responseBody(body)
                .build();
    }

    public static NotificationSendResult fail(int code, String body, String error) {
        return NotificationSendResult.builder()
                .success(false)
                .responseCode(code)
                .responseBody(body)
                .errorMessage(error)
                .build();
    }

    public static NotificationSendResult exception(String error) {
        return NotificationSendResult.builder()
                .success(false)
                .errorMessage(error)
                .build();
    }
}
