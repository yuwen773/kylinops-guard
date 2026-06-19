package com.kylinops.notification;

import com.kylinops.notification.config.NotificationChannelCommand;
import com.kylinops.notification.config.NotificationConfigurationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 通知通道连接测试服务 — P1-01 Plan 01 Task 7。
 *
 * <h3>关键设计原则</h3>
 * <ul>
 *   <li><b>同步返回</b> — 管理员点「测试」要立即看到成功/失败,不 polling</li>
 *   <li><b>不走 NotificationService.emit()</b> — 测试不读全局 {@code enabled}
 *       / {@code dryRun};即使全局关闭也可测试(这就是测试的价值)</li>
 *   <li><b>不走 dispatcher</b> — 直接调 {@link NotificationChannel#send},
 *       不经 {@code @Async} 线程池,避免 async 写入的 record 顺序错乱</li>
 *   <li><b>auditId = null</b> — 测试发送不与任何审计事件绑定</li>
 *   <li><b>FEISHU + clearSecret 拒绝</b> — 飞书签名必须有 secret</li>
 *   <li><b>外部 HTTP 失败 → FAILED 记录 + HTTP 200 响应</b> — 失败是预期结果,不是服务端异常</li>
 * </ul>
 */
@Slf4j
@Service
public class NotificationTestService {

    private static final String DEFAULT_TEST_MESSAGE = "这是一条测试消息";

    private final NotificationConfigurationService configurationService;
    private final NotificationChannelRegistry registry;
    private final NotificationRecordRepository recordRepository;
    private final NotificationPayloadSanitizer sanitizer;
    private final Clock clock;

    @Autowired
    public NotificationTestService(NotificationConfigurationService configurationService,
                                   NotificationChannelRegistry registry,
                                   NotificationRecordRepository recordRepository,
                                   NotificationPayloadSanitizer sanitizer,
                                   Clock clock) {
        this.configurationService = configurationService;
        this.registry = registry;
        this.recordRepository = recordRepository;
        this.sanitizer = sanitizer;
        this.clock = clock;
    }

    /**
     * 测试便捷构造器(供单元测试使用) — 提供一个默认的 sanitizer。
     */
    public NotificationTestService(NotificationConfigurationService configurationService,
                                   NotificationChannelRegistry registry,
                                   NotificationRecordRepository recordRepository,
                                   Clock clock) {
        this(configurationService, registry, recordRepository,
                new NotificationPayloadSanitizer(new com.fasterxml.jackson.databind.ObjectMapper()),
                clock);
    }

    /**
     * 测试已保存的通道(由 {@code channelId} 解析)。
     *
     * <p>规则:</p>
     * <ul>
     *   <li>{@code message} 为 null/空白 → 使用默认「这是一条测试消息」</li>
     *   <li>解析失败(通道不存在等)由 {@link NotificationConfigurationService#resolveForTest}
     *       抛 IllegalArgumentException,本方法透传</li>
     * </ul>
     */
    public NotificationTestResult testChannelById(String channelId, String message) {
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException("channelId is required");
        }
        NotificationChannelCommand command = NotificationChannelCommand.builder()
                .id(channelId)
                .build();
        return testInternal(command, message);
    }

    /**
     * 测试 draft 通道(未保存的临时配置)。{@code command.id()} 应由调用方
     * 自动生成(前端以 {@code test-draft-XXXXXXXX} 开头),但写入 record 时
     * 仍使用该 id 便于 UI 区分。
     */
    public NotificationTestResult testChannelDraft(NotificationChannelCommand command, String message) {
        if (command == null || command.id() == null || command.id().isBlank()) {
            throw new IllegalArgumentException("draft channel id is required");
        }
        if (command.type() == null) {
            throw new IllegalArgumentException("draft channel type is required");
        }
        return testInternal(command, message);
    }

    // ============================================================
    // 核心流程
    // ============================================================

    private NotificationTestResult testInternal(NotificationChannelCommand command, String message) {
        long started = System.nanoTime();

        // FEISHU 不允许 clearSecret:true
        if (command.type() == ChannelType.FEISHU && command.clearSecret()) {
            throw new IllegalArgumentException(
                    "FEISHU channel must not clear secret during test");
        }

        // 解析有效 ChannelConfig(已保存 → 用存储 secret;draft → 用 command.secret())
        NotificationConfig.ChannelConfig channelConfig = configurationService.resolveForTest(command);

        // draft 时把 command 上的 clearSecret 反映到 channelConfig.secret
        if (command.clearSecret()) {
            channelConfig = NotificationConfig.ChannelConfig.builder()
                    .id(channelConfig.getId())
                    .type(channelConfig.getType())
                    .enabled(channelConfig.isEnabled())
                    .url(channelConfig.getUrl())
                    .secret(null)
                    .timeoutMs(channelConfig.getTimeoutMs())
                    .build();
        }

        // 构造一个测试事件(auditId=null,eventType=TEST)
        String resolvedMessage = (message == null || message.isBlank()) ? DEFAULT_TEST_MESSAGE : message;
        NotificationEvent event = NotificationEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(NotificationEventType.TEST)
                .severity(NotificationSeverity.INFO)
                .title("通道连接测试")
                .summary("管理端主动测试: " + resolvedMessage)
                .detail(resolvedMessage)
                .auditId(null)
                .occurredAt(LocalDateTime.now(clock))
                .build();

        String maskedPayload = sanitizer.mask(event);

        // 找 handler
        Optional<NotificationChannel> handlerOpt = registry.resolveHandler(channelConfig.getType());
        if (handlerOpt.isEmpty()) {
            return persistAndBuild(event, channelConfig, maskedPayload, started,
                    NotificationSendResult.exception("未找到通道 handler: " + channelConfig.getType()));
        }

        NotificationSendResult result;
        try {
            result = handlerOpt.get().send(event, maskedPayload, channelConfig);
        } catch (Exception e) {
            log.warn("测试发送异常: channelId={}, type={}, error={}",
                    channelConfig.getId(), channelConfig.getType(), e.getMessage());
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            result = NotificationSendResult.exception("发送异常: " + msg);
        }

        return persistAndBuild(event, channelConfig, maskedPayload, started, result);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    NotificationTestResult persistAndBuild(NotificationEvent event,
                                          NotificationConfig.ChannelConfig channelConfig,
                                          String maskedPayload,
                                          long startedNanos,
                                          NotificationSendResult result) {
        long durationMs = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        LocalDateTime now = LocalDateTime.now(clock);

        NotificationStatus status = result.isSuccess() ? NotificationStatus.SENT : NotificationStatus.FAILED;

        NotificationRecord record = NotificationRecord.builder()
                .recordId(UUID.randomUUID().toString())
                .eventId(event.getEventId())
                .auditId(null) // 测试发送不绑定审计事件
                .channelId(channelConfig.getId())
                .channelType(channelConfig.getType())
                .status(status)
                .requestPayload(maskedPayload)
                .responseCode(result.getResponseCode())
                .responseBody(result.getResponseBody())
                .errorMessage(result.getErrorMessage())
                .retryCount(0)
                .sentAt(now)
                .createdAt(now)
                .eventType(NotificationEventType.TEST)
                .build();
        recordRepository.save(record);

        return NotificationTestResult.builder()
                .recordId(record.getRecordId())
                .channelId(record.getChannelId())
                .eventType(NotificationEventType.TEST)
                .status(status)
                .responseCode(result.getResponseCode())
                .errorMessage(result.getErrorMessage())
                .sentAt(now)
                .durationMs(durationMs)
                .build();
    }
}
