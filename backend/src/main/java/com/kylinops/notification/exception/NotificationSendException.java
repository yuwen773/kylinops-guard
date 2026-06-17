package com.kylinops.notification.exception;

/**
 * 通知发送异常(内部异常,不外抛到 dispatch loop 之外)。
 *
 * <p>由 Channel.send() 在遇到不可恢复错误时抛出;由 {@code NotificationDispatcher}
 * 在异步线程中统一捕获并写 FAILED record,不再向上传播。</p>
 */
public class NotificationSendException extends RuntimeException {
    public NotificationSendException(String message) {
        super(message);
    }

    public NotificationSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
