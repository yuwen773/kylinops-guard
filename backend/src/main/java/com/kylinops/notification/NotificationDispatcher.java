package com.kylinops.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 通知调度器。
 *
 * <p><b>执行模式</b>:</p>
 * <ul>
 *   <li>由 {@link NotificationService#emit(NotificationEvent)} 异步触发(@Async 线程池)</li>
 *   <li><b>不在主线程执行任何 HTTP 请求</b></li>
 *   <li>遍历 config.channels,对每个 enabled + handler 存在的通道,分别发送、分别记录状态</li>
 *   <li>dryRun=true → 遍历有效配置通道写 SKIPPED 记录,不真实发送;无有效通道时只 log debug</li>
 *   <li>AbortPolicy 拒绝 → catch RejectedExecutionException → 只 log error,不写 record</li>
 *   <li>保存 record 时 DataIntegrityViolationException → log warn,跳过该 channel(不阻碍其他)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDispatcher {

    private final NotificationChannelRegistry registry;
    private final NotificationPayloadSanitizer sanitizer;
    private final NotificationRecordRepository recordRepository;
    private final NotificationConfig config;
    private final Clock clock;

    /**
     * 异步发送入口。在 @Async notificationExecutor 线程池执行。
     *
     * <p>异步执行时发生的任何异常都写 FAILED 记录,不冒泡。</p>
     */
    @Async("notificationExecutor")
    public void dispatchAsync(NotificationEvent event) {
        try {
            doDispatch(event);
        } catch (Exception e) {
            log.error("通知调度异常: eventId={}, eventType={}",
                    event.getEventId(), event.getEventType(), e);
        }
    }

    private void doDispatch(NotificationEvent event) {
        List<NotificationConfig.ChannelConfig> configs = config.getChannels().stream()
                .filter(NotificationConfig.ChannelConfig::isEnabled)
                .filter(cc -> cc.getUrl() != null && !cc.getUrl().isBlank())
                .toList();

        if (configs.isEmpty()) {
            log.debug("无有效的通知通道配置,跳过通知: eventType={}, eventId={}",
                    event.getEventType(), event.getEventId());
            return;
        }

        String maskedPayload = sanitizer.mask(event);

        for (NotificationConfig.ChannelConfig channelConfig : configs) {
            Optional<NotificationChannel> handlerOpt =
                    registry.resolveHandler(channelConfig.getType());

            if (handlerOpt.isEmpty()) {
                log.warn("未找到通知通道 handler: type={}, channelId={}",
                        channelConfig.getType(), channelConfig.getId());
                continue;
            }

            NotificationChannel handler = handlerOpt.get();

            if (!handler.supports(event, channelConfig)) {
                continue;
            }

            // dryRun — 写 SKIPPED 记录,不真实发送
            if (config.isDryRun()) {
                writeSkippedRecord(event, channelConfig, maskedPayload);
                continue;
            }

            // 创建 PENDING 记录(唯一约束冲突时跳过)
            NotificationRecord record = createPendingRecord(event, channelConfig, maskedPayload);
            if (record == null) {
                continue;
            }

            try {
                NotificationSendResult result = handler.send(event, maskedPayload, channelConfig);
                record.setStatus(result.isSuccess()
                        ? NotificationStatus.SENT : NotificationStatus.FAILED);
                record.setResponseCode(result.getResponseCode());
                record.setResponseBody(result.getResponseBody());
                record.setErrorMessage(result.getErrorMessage());
            } catch (Exception e) {
                log.error("通道发送异常: channelId={}, channelType={}, eventId={}",
                        channelConfig.getId(), channelConfig.getType(), event.getEventId(), e);
                record.setStatus(NotificationStatus.FAILED);
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                record.setErrorMessage("发送异常: " + msg);
            }
            record.setSentAt(LocalDateTime.now(clock));
            recordRepository.save(record);
        }
    }

    private NotificationRecord createPendingRecord(NotificationEvent event,
            NotificationConfig.ChannelConfig channelConfig, String maskedPayload) {
        NotificationRecord record = NotificationRecord.builder()
                .recordId(UUID.randomUUID().toString())
                .eventId(event.getEventId())
                .auditId(event.getAuditId() != null ? event.getAuditId() : "")
                .channelId(channelConfig.getId())
                .channelType(channelConfig.getType())
                .status(NotificationStatus.PENDING)
                .requestPayload(maskedPayload)
                .retryCount(0)
                .createdAt(LocalDateTime.now(clock))
                .build();
        try {
            return recordRepository.save(record);
        } catch (DataIntegrityViolationException e) {
            log.warn("通知记录已存在,跳过: eventId={}, channelId={}",
                    event.getEventId(), channelConfig.getId());
            return null; // 跳过该 channel(不影响其他 channel)
        }
    }

    private void writeSkippedRecord(NotificationEvent event,
            NotificationConfig.ChannelConfig channelConfig, String maskedPayload) {
        NotificationRecord record = NotificationRecord.builder()
                .recordId(UUID.randomUUID().toString())
                .eventId(event.getEventId())
                .auditId(event.getAuditId() != null ? event.getAuditId() : "")
                .channelId(channelConfig.getId())
                .channelType(channelConfig.getType())
                .status(NotificationStatus.SKIPPED)
                .requestPayload(maskedPayload)
                .retryCount(0)
                .createdAt(LocalDateTime.now(clock))
                .sentAt(LocalDateTime.now(clock))
                .build();
        try {
            recordRepository.save(record);
        } catch (DataIntegrityViolationException e) {
            log.warn("SKIPPED 记录已存在,跳过: eventId={}, channelId={}",
                    event.getEventId(), channelConfig.getId());
        }
        log.debug("dry-run 跳过通知: eventType={}, channelId={}",
                event.getEventType(), channelConfig.getId());
    }
}
