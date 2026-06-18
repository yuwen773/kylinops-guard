package com.kylinops.notification;

import com.kylinops.notification.config.NotificationConfigurationService;
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
 *   <li>判断 {@link com.kylinops.notification.config.RuntimeNotificationConfig#enabled}
 *       — false 时直接 return</li>
 *   <li>补 eventId / occurredAt</li>
 *   <li>调 {@link NotificationDispatcher#dispatchAsync(NotificationEvent)} — 异步发送</li>
 * </ul>
 *
 * <p><b>运行时配置契约 (P1-01 Plan 01 — Task 4)</b>:</p>
 * <ul>
 *   <li>不再持有启动期 {@link NotificationConfig};每次 emit 时从
 *       {@link NotificationConfigurationService#snapshot()} 读取最新已发布的快照</li>
 *   <li>快照仅在事务 after-commit 后由 ConfigurationService 发布,杜绝回滚前状态泄漏</li>
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

    private final NotificationConfigurationService configurationService;
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
            // 全局开关检查(必须在最前)— 从运行时快照读取,保证业务看到的开关与
            // Management API 最近一次 updateSettings 完全一致。
            if (!configurationService.snapshot().enabled()) {
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
