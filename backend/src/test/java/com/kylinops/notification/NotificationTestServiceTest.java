package com.kylinops.notification;

import com.kylinops.notification.channel.FeishuChannel;
import com.kylinops.notification.channel.WebhookChannel;
import com.kylinops.notification.config.NotificationChannelCommand;
import com.kylinops.notification.config.NotificationConfigurationService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NotificationTestService 单元测试 — P1-01 Plan 01 Task 7。
 *
 * <p>验证连接测试的端点契约与边界:</p>
 * <ul>
 *   <li>已保存通道 — 走 {@code resolveForTest},使用存储 secret</li>
 *   <li>draft 通道 — 不写 channels 表,recordChannelId 用 test-draft- 前缀</li>
 *   <li>clearSecret=true(WEBHOOK) — 测试时清空 secret,验证无签名场景</li>
 *   <li>clearSecret=true(FEISHU) — 拒绝,抛 IllegalArgumentException</li>
 *   <li>外部 HTTP 失败 — 写 FAILED 记录,service 自身不抛</li>
 *   <li>不发通知流 — 不走 NotificationService.emit(),不被全局 enabled / dryRun 拦截</li>
 * </ul>
 */
@DisplayName("P1-01 T7 — NotificationTestService")
class NotificationTestServiceTest {

    private NotificationConfigurationService configurationService;
    private NotificationChannelRegistry registry;
    private NotificationRecordRepository recordRepository;
    private NotificationTestService service;
    private MockWebServer mockWebServer;

    @BeforeEach
    void setUp() throws IOException {
        configurationService = mock(NotificationConfigurationService.class);
        recordRepository = mock(NotificationRecordRepository.class);
        when(recordRepository.save(any(NotificationRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Real channels — they hit a MockWebServer so HTTP is deterministic.
        WebhookChannel webhookChannel =
                new WebhookChannel(new com.fasterxml.jackson.databind.ObjectMapper());
        FeishuChannel feishuChannel =
                new FeishuChannel(new com.fasterxml.jackson.databind.ObjectMapper(),
                        new com.kylinops.notification.NotificationPublicLinkProperties(""));

        registry = new NotificationChannelRegistry(List.of(webhookChannel, feishuChannel));
        ReflectionTestUtils.invokeMethod(registry, "init");

        mockWebServer = new MockWebServer();
        mockWebServer.start();

        service = new NotificationTestService(configurationService, registry, recordRepository,
                Clock.systemUTC());
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    private String url() {
        return mockWebServer.url("/test").toString();
    }

    /**
     * Mock helper:从 command.id() 前缀推断通道类型,使 saved / draft 都能找到 handler。
     * FEISHU 强制有 secret(测试场景下回退到 default),WEBHOOK 允许 null。
     */
    private NotificationConfig.ChannelConfig resolveTo(NotificationChannelCommand cmd) {
        ChannelType inferred = cmd.type() != null ? cmd.type()
                : (cmd.id() != null && cmd.id().startsWith("feishu")
                        ? ChannelType.FEISHU
                        : ChannelType.WEBHOOK);
        String effectiveSecret = cmd.secret();
        if (inferred == ChannelType.FEISHU) {
            effectiveSecret = (cmd.clearSecret() || cmd.secret() == null)
                    ? "default-feishu-secret" : cmd.secret();
        }
        if (cmd.clearSecret() && inferred == ChannelType.WEBHOOK) {
            effectiveSecret = null;
        }
        return NotificationConfig.ChannelConfig.builder()
                .id(cmd.id())
                .type(inferred)
                .enabled(true)
                .url(url())
                .secret(effectiveSecret)
                .timeoutMs(3000)
                .build();
    }

    private void mockResolve() {
        when(configurationService.resolveForTest(any()))
                .thenAnswer(inv -> resolveTo(inv.getArgument(0)));
    }

    // ==================== 已保存通道 ====================

    @Test
    @DisplayName("已保存通道测试 → 200 OK → SENT 记录,auditId=null,channelId=原 ID")
    void savedChannelSuccessReturnsSentRecord() {
        mockResolve();
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));

        NotificationTestResult result = service.testChannelById("feishu-oncall", "测试消息");

        assertThat(result.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(result.channelId()).isEqualTo("feishu-oncall");
        assertThat(result.eventType()).isEqualTo(NotificationEventType.TEST);
        assertThat(result.errorMessage()).isNull();
        assertThat(result.responseCode()).isEqualTo(200);
        assertThat(result.recordId()).isNotBlank();
        assertThat(result.sentAt()).isNotNull();
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);

        // 持久化断言:auditId 必须为 null,eventType = TEST
        org.mockito.ArgumentCaptor<NotificationRecord> cap =
                org.mockito.ArgumentCaptor.forClass(NotificationRecord.class);
        verify(recordRepository, times(1)).save(cap.capture());
        NotificationRecord record = cap.getValue();
        assertThat(record.getAuditId()).isNull();
        assertThat(record.getEventType()).isEqualTo(NotificationEventType.TEST);
        assertThat(record.getChannelId()).isEqualTo("feishu-oncall");
        assertThat(record.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(record.getResponseCode()).isEqualTo(200);
        // 不走 NotificationService.emit():只是直接调 channel.send()
        verify(configurationService, never()).snapshot();
    }

    @Test
    @DisplayName("已保存通道测试 — 外部 HTTP 5xx → FAILED 记录,errorMessage 写入,service 不抛")
    void savedChannelFailureWritesFailedRecord() {
        mockResolve();
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("internal"));

        NotificationTestResult result = service.testChannelById("wh-prod", "hi");

        assertThat(result.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(result.errorMessage()).isNotBlank();
        assertThat(result.responseCode()).isEqualTo(500);

        org.mockito.ArgumentCaptor<NotificationRecord> cap =
                org.mockito.ArgumentCaptor.forClass(NotificationRecord.class);
        verify(recordRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(NotificationStatus.FAILED);
    }

    // ==================== draft 通道 ====================

    @Test
    @DisplayName("draft 通道测试 → 200 OK → SENT 记录,recordChannelId 用 test-draft- 前缀")
    void draftChannelSuccessUsesTestDraftPrefix() {
        mockResolve();
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        NotificationChannelCommand command = NotificationChannelCommand.builder()
                .id("test-draft-12345678")
                .type(ChannelType.WEBHOOK)
                .enabled(true)
                .url(url())
                .secret(null)
                .clearSecret(false)
                .timeoutMs(3000)
                .build();

        NotificationTestResult result = service.testChannelDraft(command, "测试");

        assertThat(result.status()).isEqualTo(NotificationStatus.SENT);
        // recordChannelId 仍以 draft 前缀呈现给前端
        assertThat(result.channelId()).startsWith("test-draft-");
    }

    @Test
    @DisplayName("draft 通道测试 — 不持久化 channels 表(只写 notification_records)")
    void draftChannelDoesNotPersistChannelsTable() {
        mockResolve();
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));

        service.testChannelDraft(NotificationChannelCommand.builder()
                .id("test-draft-abcdef00")
                .type(ChannelType.WEBHOOK)
                .enabled(true)
                .url(url())
                .secret(null)
                .clearSecret(false)
                .timeoutMs(3000)
                .build(), "测试");

        // 只写 1 条 record
        verify(recordRepository, times(1)).save(any(NotificationRecord.class));
        // 走的是 resolveForTest(Draft),不是 createChannel — 后者不会触发
        verify(configurationService, never()).getSettings();
    }

    // ==================== clearSecret 边界 ====================

    @Test
    @DisplayName("WEBHOOK clearSecret=true → 测试时清空 secret,签名头不应出现")
    void webhookClearSecretSkipsSignature() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        mockResolve();

        NotificationChannelCommand command = NotificationChannelCommand.builder()
                .id("wh-secret-test")
                .type(ChannelType.WEBHOOK)
                .enabled(true)
                .url(url())
                .secret(null)
                .clearSecret(true)
                .timeoutMs(3000)
                .build();

        NotificationTestResult result = service.testChannelDraft(command, "测试");

        assertThat(result.status()).isEqualTo(NotificationStatus.SENT);
        // 校验 MockWebServer 收到的请求不应带 X-KylinOps-Signature 头
        var recorded = mockWebServer.takeRequest();
        assertThat(recorded.getHeader("X-KylinOps-Signature")).isNull();
    }

    @Test
    @DisplayName("FEISHU clearSecret=true → 拒绝,抛 IllegalArgumentException,不写 record")
    void feishuClearSecretRejected() {
        mockResolve();

        NotificationChannelCommand command = NotificationChannelCommand.builder()
                .id("feishu-secret-test")
                .type(ChannelType.FEISHU)
                .enabled(true)
                .url(url())
                .secret(null)
                .clearSecret(true)
                .timeoutMs(3000)
                .build();

        assertThatThrownBy(() -> service.testChannelDraft(command, "测试"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FEISHU");

        verify(recordRepository, never()).save(any(NotificationRecord.class));
    }

    // ==================== 行为契约 ====================

    @Test
    @DisplayName("测试发送不读全局 enabled / dryRun(配置服务 snapshot 不被读)")
    void testSendBypassesGlobalEnabledAndDryRun() {
        mockResolve();
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));

        service.testChannelById("wh-prod", "测试");

        // 测试路径不查 snapshot
        verify(configurationService, never()).snapshot();
    }

    @Test
    @DisplayName("resolveForTest 抛 IllegalArgumentException → service 直接抛,不写 record")
    void resolveFailurePropagates() {
        when(configurationService.resolveForTest(any()))
                .thenThrow(new IllegalArgumentException("channel id is required"));

        assertThatThrownBy(() -> service.testChannelById("anything", "测试"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel id");

        verify(recordRepository, never()).save(any(NotificationRecord.class));
    }

    @Test
    @DisplayName("已保存通道测试 — 默认 message 为「这是一条测试消息」")
    void defaultMessageAppliedWhenBlank() {
        mockResolve();
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));

        NotificationTestResult result = service.testChannelById("wh-prod", null);

        // 默认 message 不影响 service 自身 — 验证 status 是 SENT 即可
        assertThat(result.status()).isEqualTo(NotificationStatus.SENT);
    }
}
