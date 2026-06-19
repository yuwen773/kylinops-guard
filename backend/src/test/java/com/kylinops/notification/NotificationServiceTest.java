package com.kylinops.notification;

import com.kylinops.notification.config.NotificationConfigurationService;
import com.kylinops.notification.config.RuntimeNotificationConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NotificationService 单元测试 — 验证从 {@link RuntimeNotificationConfig} 快照
 * 读取 enabled 开关的契约(P1-01 Plan 01 — Task 4)。
 *
 * <p>关键变更(Task 4):NotificationService 不再直接持有 {@link NotificationConfig}
 * (启动期绑定),改为持有 {@link NotificationConfigurationService},每次 emit 时
 * 调 {@link NotificationConfigurationService#snapshot()} 读取当前已发布的快照。</p>
 */
class NotificationServiceTest {

    private NotificationConfigurationService configurationService() {
        NotificationConfigurationService svc = mock(NotificationConfigurationService.class);
        when(svc.snapshot()).thenReturn(new RuntimeNotificationConfig(true, false, List.of()));
        return svc;
    }

    @Test
    void emit_disabled_skipsDispatcher() {
        NotificationConfigurationService configurationService = mock(NotificationConfigurationService.class);
        when(configurationService.snapshot()).thenReturn(
                new RuntimeNotificationConfig(false, false, List.of()));
        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        Clock clock = Clock.systemUTC();
        NotificationService service = new NotificationService(configurationService, dispatcher, clock);

        service.emit(NotificationEvent.builder()
                .eventType(NotificationEventType.L4_BLOCK)
                .severity(NotificationSeverity.CRITICAL)
                .title("t").summary("s").build());

        verify(dispatcher, never()).dispatchAsync(any());
    }

    @Test
    void emit_nullEvent_skipsDispatcher() {
        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        NotificationService service = new NotificationService(configurationService(), dispatcher, Clock.systemUTC());

        service.emit(null);
        verify(dispatcher, never()).dispatchAsync(any());
    }

    @Test
    void emit_enabled_fillsEventIdAndOccurredAt() {
        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        Clock clock = Clock.fixed(java.time.Instant.parse("2026-06-17T00:00:00Z"), java.time.ZoneOffset.UTC);
        NotificationService service = new NotificationService(configurationService(), dispatcher, clock);

        NotificationEvent e = NotificationEvent.builder()
                .eventType(NotificationEventType.L4_BLOCK)
                .severity(NotificationSeverity.CRITICAL)
                .title("t").summary("s").auditId("a")
                .build();
        service.emit(e);

        assertNotNull(e.getEventId(), "Service 应补 eventId");
        assertNotNull(e.getOccurredAt(), "Service 应补 occurredAt");
        // 验证 UUID 格式
        UUID.fromString(e.getEventId());
        // 验证 occurredAt 用注入的 Clock
        assertEquals(LocalDateTime.now(clock), e.getOccurredAt());

        ArgumentCaptor<NotificationEvent> cap = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(dispatcher, times(1)).dispatchAsync(cap.capture());
        assertEquals(e.getEventId(), cap.getValue().getEventId());
    }

    @Test
    void emit_preservesExistingEventIdAndOccurredAt() {
        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        NotificationService service = new NotificationService(configurationService(), dispatcher, Clock.systemUTC());

        LocalDateTime fixed = LocalDateTime.of(2026, 1, 1, 0, 0);
        NotificationEvent e = NotificationEvent.builder()
                .eventType(NotificationEventType.L2_CONFIRM_REQUIRED)
                .eventId("preset-id")
                .occurredAt(fixed)
                .title("t").summary("s").build();
        service.emit(e);

        assertEquals("preset-id", e.getEventId(), "已存在的 eventId 不应被覆盖");
        assertEquals(fixed, e.getOccurredAt(), "已存在的 occurredAt 不应被覆盖");
        verify(dispatcher, times(1)).dispatchAsync(any());
    }

    @Test
    void emit_dispatcherThrows_doesNotPropagate() {
        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        org.mockito.Mockito.doThrow(new RuntimeException("dispatcher boom"))
                .when(dispatcher).dispatchAsync(any());
        NotificationService service = new NotificationService(configurationService(), dispatcher, Clock.systemUTC());

        // 不应抛
        service.emit(NotificationEvent.builder()
                .eventType(NotificationEventType.L4_BLOCK)
                .title("t").summary("s").build());

        // 确认 dispatcher 被调用过(异常被 service 内部 catch)
        verify(dispatcher, times(1)).dispatchAsync(any());
    }

    @Test
    void emit_dispatcherRejected_doesNotPropagate() {
        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        org.mockito.Mockito.doThrow(new RejectedExecutionException("queue full"))
                .when(dispatcher).dispatchAsync(any());
        NotificationService service = new NotificationService(configurationService(), dispatcher, Clock.systemUTC());

        service.emit(NotificationEvent.builder()
                .eventType(NotificationEventType.L4_BLOCK)
                .title("t").summary("s").build());

        verify(dispatcher, times(1)).dispatchAsync(any());
    }

    @Test
    void emit_readsSnapshotFromConfigurationService() {
        // Task 4 核心契约:enabled 必须从 snapshot() 读取,而不是从启动期配置读取
        NotificationConfigurationService configurationService = mock(NotificationConfigurationService.class);
        when(configurationService.snapshot()).thenReturn(
                new RuntimeNotificationConfig(false, false, List.of()));
        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        NotificationService service = new NotificationService(configurationService, dispatcher, Clock.systemUTC());

        service.emit(NotificationEvent.builder()
                .eventType(NotificationEventType.L4_BLOCK)
                .title("t").summary("s").build());

        // 验证:即使从未单独 setEnabled,只要 snapshot.enabled()=false,dispatcher 不被调
        verify(configurationService, times(1)).snapshot();
        verify(dispatcher, never()).dispatchAsync(any());
    }
}