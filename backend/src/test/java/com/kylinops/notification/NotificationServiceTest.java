package com.kylinops.notification;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NotificationService 单元测试 — 验证 enable=false 短路、补 eventId/occurredAt、异常不冒泡。
 */
class NotificationServiceTest {

    @Test
    void emit_disabled_skipsDispatcher() {
        NotificationConfig config = new NotificationConfig();
        config.setEnabled(false);
        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        Clock clock = Clock.systemUTC();
        NotificationService service = new NotificationService(config, dispatcher, clock);

        service.emit(NotificationEvent.builder()
                .eventType(NotificationEventType.L4_BLOCK)
                .severity(NotificationSeverity.CRITICAL)
                .title("t").summary("s").build());

        verify(dispatcher, never()).dispatchAsync(any());
    }

    @Test
    void emit_nullEvent_skipsDispatcher() {
        NotificationConfig config = new NotificationConfig();
        config.setEnabled(true);
        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        NotificationService service = new NotificationService(config, dispatcher, Clock.systemUTC());

        service.emit(null);
        verify(dispatcher, never()).dispatchAsync(any());
    }

    @Test
    void emit_enabled_fillsEventIdAndOccurredAt() {
        NotificationConfig config = new NotificationConfig();
        config.setEnabled(true);
        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        Clock clock = Clock.fixed(java.time.Instant.parse("2026-06-17T00:00:00Z"), java.time.ZoneOffset.UTC);
        NotificationService service = new NotificationService(config, dispatcher, clock);

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
        NotificationConfig config = new NotificationConfig();
        config.setEnabled(true);
        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        NotificationService service = new NotificationService(config, dispatcher, Clock.systemUTC());

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
        NotificationConfig config = new NotificationConfig();
        config.setEnabled(true);
        NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
        org.mockito.Mockito.doThrow(new RuntimeException("dispatcher boom"))
                .when(dispatcher).dispatchAsync(any());
        NotificationService service = new NotificationService(config, dispatcher, Clock.systemUTC());

        // 不应抛
        service.emit(NotificationEvent.builder()
                .eventType(NotificationEventType.L4_BLOCK)
                .title("t").summary("s").build());

        // 确认 dispatcher 被调用过(异常被 service 内部 catch)
        verify(dispatcher, times(1)).dispatchAsync(any());
    }
}
