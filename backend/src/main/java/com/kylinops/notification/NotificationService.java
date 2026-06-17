package com.kylinops.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 通知服务 — AgentOrchestrator 的唯一入口。
 *
 * <p><b>职责</b>(仅同步操作,无 DB 写入、无 HTTP 请求):</p>
 * <ul>
 *   <li>判断 {@link NotificationConfig#enabled} — false 时直接 return</li>
 *   <li>补 eventId / occurredAt</li>
 *   <li>调 {@link NotificationDispatcher#dispatchAsync(NotificationEvent)} — 异步发送</li>
 * </ul>
 *
 * <p><b>安全约束</b>:</p>
 * <ul>
 *   <li>emit() 永远不抛异常;任何异常 log.error 即可</li>
 *   <li>enabled=false 时不做任何动作(不写 record、不调 dispatcher)</li>
 *   <li>不写 __pending__ 假 record(Record 由 Dispatcher 按 channel 创建)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationConfig config;
    private final NotificationDispatcher dispatcher;
    private final Clock clock;

    /**
     * 发出通知事件。
     *
     * <p>调用方(AgentOrchestrator)可以确保事件核心字段(eventType / severity / auditId等)已填充;
     * eventId 和 occurredAt 在未填时由本方法补全。</p>
     */
    public void emit(NotificationEvent event) {
        try {
            // 全局开关检查(必须在最前)
            if (!config.isEnabled()) {
                log.debug("通知已禁用,跳过: eventType={}", event != null ? event.getEventType() : null);
                return;
            }
            if (event == null) {
                log.debug("event 为空,跳过");
                return;
            }

            // 补全必填字段
            if (event.getEventId() == null) {
                event.setEventId(UUID.randomUUID().toString());
            }
            if (event.getOccurredAt() == null) {
                event.setOccurredAt(LocalDateTime.now(clock));
            }

            // 异步调度(Dispatcher 在 notificationExecutor 线程中创建 Record、发送通知并更新状态)
            dispatcher.dispatchAsync(event);

        } catch (Exception e) {
            log.error("通知 emit 异常: auditId={}, eventType={}",
                    event != null ? event.getAuditId() : null,
                    event != null ? event.getEventType() : null, e);
            // 永不冒泡 — 通知失败不影响 Agent 主流程
        }
    }
}
