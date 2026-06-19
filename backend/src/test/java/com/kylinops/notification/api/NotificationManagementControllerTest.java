package com.kylinops.notification.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kylinops.notification.ChannelType;
import com.kylinops.notification.NotificationEventType;
import com.kylinops.notification.NotificationRecord;
import com.kylinops.notification.NotificationStatus;
import com.kylinops.notification.NotificationTestResult;
import com.kylinops.notification.NotificationTestService;
import com.kylinops.notification.config.NotificationChannelCommand;
import com.kylinops.notification.config.NotificationChannelModel;
import com.kylinops.notification.config.NotificationConfigurationConflictException;
import com.kylinops.notification.config.NotificationConfigurationService;
import com.kylinops.notification.config.NotificationSettingsModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * NotificationManagementController MockMvc 测试。
 *
 * <p>验证管理 API 的认证、校验、正常流程、乐观锁 409 冲突处理,
 * 以及 P1-01 Task 7 新增的连接测试端点契约。</p>
 */
@WebMvcTest(NotificationManagementController.class)
@WithMockUser
@DisplayName("NotificationManagementController — 通知配置管理 API")
class NotificationManagementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private NotificationConfigurationService configService;

    @MockBean
    private NotificationTestService testService;

    private NotificationSettingsModel settingsModel;
    private NotificationChannelModel webhookChannel;
    private NotificationChannelModel feishuChannel;

    @BeforeEach
    void setUp() {
        settingsModel = NotificationSettingsModel.builder()
                .enabled(true)
                .dryRun(false)
                .version(1L)
                .build();

        webhookChannel = NotificationChannelModel.builder()
                .id("webhook-default")
                .type(ChannelType.WEBHOOK)
                .enabled(true)
                .url("https://hooks.example.com/webhook")
                .hasSecret(false)
                .timeoutMs(3000)
                .version(3L)
                .createdAt(LocalDateTime.of(2026, 6, 1, 10, 0))
                .updatedAt(LocalDateTime.of(2026, 6, 15, 14, 0))
                .build();

        feishuChannel = NotificationChannelModel.builder()
                .id("feishu-prod")
                .type(ChannelType.FEISHU)
                .enabled(true)
                .url("https://open.feishu.cn/open-apis/bot/v2/hook/xxx")
                .hasSecret(true)
                .timeoutMs(5000)
                .version(7L)
                .createdAt(LocalDateTime.of(2026, 6, 2, 9, 0))
                .updatedAt(LocalDateTime.of(2026, 6, 16, 11, 0))
                .build();
    }

    // ==================== GET /api/notification/settings ====================

    @Test
    @DisplayName("GET /api/notification/settings → 200 + 设置 + 全量通道列表(不含 secret,含 lastTestResult)")
    void getSettingsReturnsSettingsWithChannels() throws Exception {
        NotificationTestRecordSummary lastTest = NotificationTestRecordSummary.builder()
                .recordId("rec-1")
                .channelId("webhook-default")
                .eventType(NotificationEventType.TEST)
                .status(NotificationStatus.SENT)
                .responseCode(200)
                .sentAt(LocalDateTime.of(2026, 6, 18, 12, 0))
                .durationMs(150L)
                .build();
        NotificationChannelModel withTest = NotificationChannelModel.builder()
                .id(webhookChannel.id())
                .type(webhookChannel.type())
                .enabled(webhookChannel.enabled())
                .url(webhookChannel.url())
                .hasSecret(webhookChannel.hasSecret())
                .timeoutMs(webhookChannel.timeoutMs())
                .version(webhookChannel.version())
                .createdAt(webhookChannel.createdAt())
                .updatedAt(webhookChannel.updatedAt())
                .lastTestResult(lastTest)
                .build();
        when(configService.getSettings()).thenReturn(settingsModel);
        when(configService.listChannels()).thenReturn(List.of(withTest, feishuChannel));

        mockMvc.perform(get("/api/notification/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.dryRun").value(false))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.channels").isArray())
                .andExpect(jsonPath("$.data.channels.length()").value(2))
                // 第一个通道:无 secret,secretConfigured=false,带 lastTestResult
                .andExpect(jsonPath("$.data.channels[0].id").value("webhook-default"))
                .andExpect(jsonPath("$.data.channels[0].type").value("WEBHOOK"))
                .andExpect(jsonPath("$.data.channels[0].secretConfigured").value(false))
                .andExpect(jsonPath("$.data.channels[0].secret").doesNotExist())
                .andExpect(jsonPath("$.data.channels[0].encryptedSecret").doesNotExist())
                .andExpect(jsonPath("$.data.channels[0].lastTestResult.recordId").value("rec-1"))
                .andExpect(jsonPath("$.data.channels[0].lastTestResult.status").value("SENT"))
                // 第二个通道:有 secret,secretConfigured=true,无测试记录
                .andExpect(jsonPath("$.data.channels[1].id").value("feishu-prod"))
                .andExpect(jsonPath("$.data.channels[1].secretConfigured").value(true))
                .andExpect(jsonPath("$.data.channels[1].secret").doesNotExist())
                .andExpect(jsonPath("$.data.channels[1].encryptedSecret").doesNotExist())
                .andExpect(jsonPath("$.data.channels[1].lastTestResult").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/notification/settings → 通道 field 完整性")
    void getSettingsChannelFields() throws Exception {
        when(configService.getSettings()).thenReturn(settingsModel);
        when(configService.listChannels()).thenReturn(List.of(feishuChannel));

        mockMvc.perform(get("/api/notification/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.channels[0].id").value("feishu-prod"))
                .andExpect(jsonPath("$.data.channels[0].type").value("FEISHU"))
                .andExpect(jsonPath("$.data.channels[0].enabled").value(true))
                .andExpect(jsonPath("$.data.channels[0].url").value("https://open.feishu.cn/open-apis/bot/v2/hook/xxx"))
                .andExpect(jsonPath("$.data.channels[0].secretConfigured").value(true))
                .andExpect(jsonPath("$.data.channels[0].timeoutMs").value(5000))
                .andExpect(jsonPath("$.data.channels[0].version").value(7))
                .andExpect(jsonPath("$.data.channels[0].createdAt").isString())
                .andExpect(jsonPath("$.data.channels[0].updatedAt").isString());
    }

    // ==================== PUT /api/notification/settings ====================

    @Test
    @DisplayName("PUT /api/notification/settings → 200 + 更新后的设置")
    void updateSettingsReturnsUpdated() throws Exception {
        NotificationSettingsModel updated = NotificationSettingsModel.builder()
                .enabled(false)
                .dryRun(true)
                .version(2L)
                .build();
        when(configService.updateSettings(any())).thenReturn(updated);
        when(configService.getSettings()).thenReturn(updated);
        when(configService.listChannels()).thenReturn(List.of());

        String body = """
                {"enabled":false,"dryRun":true,"version":1}
                """;

        mockMvc.perform(put("/api/notification/settings")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.dryRun").value(true))
                .andExpect(jsonPath("$.data.version").value(2));
    }

    // ==================== POST /api/notification/channels ====================

    @Test
    @DisplayName("POST /api/notification/channels → 200 + 新建通道")
    void createChannelReturnsCreated() throws Exception {
        NotificationChannelModel created = NotificationChannelModel.builder()
                .id("new-channel")
                .type(ChannelType.WEBHOOK)
                .enabled(true)
                .url("https://hooks.example.com/new")
                .hasSecret(false)
                .timeoutMs(3000)
                .version(0L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        when(configService.createChannel(any())).thenReturn(created);

        String body = """
                {"channelId":"new-channel","type":"WEBHOOK","enabled":true,"url":"https://hooks.example.com/new","timeoutMs":3000}
                """;

        mockMvc.perform(post("/api/notification/channels")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("new-channel"))
                .andExpect(jsonPath("$.data.type").value("WEBHOOK"))
                .andExpect(jsonPath("$.data.secretConfigured").value(false))
                .andExpect(jsonPath("$.data.secret").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/notification/channels → 通道 ID 为空时返回 400")
    void createChannelMissingIdReturns400() throws Exception {
        String body = """
                {"type":"WEBHOOK","enabled":true,"url":"https://example.com"}
                """;

        mockMvc.perform(post("/api/notification/channels")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("channelId is required")));
    }

    // ==================== PUT /api/notification/channels/{channelId} ====================

    @Test
    @DisplayName("PUT /api/notification/channels/{id} → 200 + 更新后的通道")
    void updateChannelReturnsUpdated() throws Exception {
        NotificationChannelModel updated = NotificationChannelModel.builder()
                .id("feishu-prod")
                .type(ChannelType.FEISHU)
                .enabled(false)
                .url("https://open.feishu.cn/open-apis/bot/v2/hook/new")
                .hasSecret(true)
                .timeoutMs(5000)
                .version(8L)
                .createdAt(LocalDateTime.of(2026, 6, 2, 9, 0))
                .updatedAt(LocalDateTime.now())
                .build();
        when(configService.updateChannel(eq("feishu-prod"), any())).thenReturn(updated);

        String body = """
                {"type":"FEISHU","enabled":false,"url":"https://open.feishu.cn/open-apis/bot/v2/hook/new","version":7}
                """;

        mockMvc.perform(put("/api/notification/channels/feishu-prod")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("feishu-prod"))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.version").value(8));
    }

    @Test
    @DisplayName("PUT /api/notification/channels/{id} → version 为空返回 400")
    void updateChannelMissingVersionReturns400() throws Exception {
        String body = """
                {"type":"WEBHOOK","enabled":true,"url":"https://example.com"}
                """;

        mockMvc.perform(put("/api/notification/channels/webhook-default")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("version is required")));
    }

    @Test
    @DisplayName("PUT /api/notification/channels/{id} → 版本冲突时返回 409 Conflict")
    void updateChannelVersionConflictReturns409() throws Exception {
        when(configService.updateChannel(eq("feishu-prod"), any()))
                .thenThrow(new NotificationConfigurationConflictException(
                        "channel version mismatch: expected=7 actual=10"));

        String staleVersionJson = """
                {"type":"FEISHU","enabled":true,"url":"https://open.feishu.cn/open-apis/bot/v2/hook/xxx","version":7}
                """;

        mockMvc.perform(put("/api/notification/channels/feishu-prod")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staleVersionJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("version mismatch")));
    }

    @Test
    @DisplayName("PUT /api/notification/channels/{id} → 通道不存在时返回 404")
    void updateChannelNotFoundReturns404() throws Exception {
        when(configService.updateChannel(eq("nonexistent"), any()))
                .thenThrow(new NotificationConfigurationConflictException("channel not found: nonexistent"));

        String body = """
                {"type":"WEBHOOK","enabled":true,"url":"https://example.com","version":1}
                """;

        mockMvc.perform(put("/api/notification/channels/nonexistent")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    // ==================== DELETE /api/notification/channels/{channelId} ====================

    @Test
    @DisplayName("DELETE /api/notification/channels/{id} → 200")
    void deleteChannelReturnsOk() throws Exception {
        mockMvc.perform(delete("/api/notification/channels/webhook-default")
                        .with(csrf())
                        .param("version", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(configService).deleteChannel("webhook-default", 3L);
    }

    // ==================== POST /api/notification/channels/test (P1-01 Task 7) ====================

    @Test
    @DisplayName("POST /channels/test saved 模式 → 调 testChannelById,返回 SENT 结果")
    void testChannelSavedMode() throws Exception {
        NotificationTestResult result = NotificationTestResult.builder()
                .recordId(UUID.randomUUID().toString())
                .channelId("feishu-prod")
                .eventType(NotificationEventType.TEST)
                .status(NotificationStatus.SENT)
                .responseCode(200)
                .sentAt(LocalDateTime.of(2026, 6, 19, 10, 0))
                .durationMs(120L)
                .build();
        when(testService.testChannelById(eq("feishu-prod"), eq("测试消息"))).thenReturn(result);

        String body = """
                {"channelId":"feishu-prod","message":"测试消息"}
                """;

        mockMvc.perform(post("/api/notification/channels/test")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.channelId").value("feishu-prod"))
                .andExpect(jsonPath("$.data.status").value("SENT"))
                .andExpect(jsonPath("$.data.responseCode").value(200))
                .andExpect(jsonPath("$.data.eventType").value("TEST"))
                .andExpect(jsonPath("$.data.recordId").isNotEmpty())
                .andExpect(jsonPath("$.data.sentAt").isString())
                .andExpect(jsonPath("$.data.durationMs").value(120));

        verify(testService).testChannelById("feishu-prod", "测试消息");
    }

    @Test
    @DisplayName("POST /channels/test draft 模式 → 调 testChannelDraft,recordChannelId 以 test-draft- 前缀")
    void testChannelDraftMode() throws Exception {
        NotificationTestResult result = NotificationTestResult.builder()
                .recordId(UUID.randomUUID().toString())
                .channelId("test-draft-12345678")
                .eventType(NotificationEventType.TEST)
                .status(NotificationStatus.SENT)
                .responseCode(200)
                .sentAt(LocalDateTime.of(2026, 6, 19, 10, 0))
                .durationMs(80L)
                .build();
        org.mockito.ArgumentCaptor<NotificationChannelCommand> cap =
                org.mockito.ArgumentCaptor.forClass(NotificationChannelCommand.class);
        when(testService.testChannelDraft(cap.capture(), eq("draft test"))).thenReturn(result);

        String body = """
                {"type":"WEBHOOK","enabled":true,"url":"https://example.com/hook",
                 "secret":"sec_abc","clearSecret":false,"timeoutMs":3000,"message":"draft test"}
                """;

        mockMvc.perform(post("/api/notification/channels/test")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("SENT"))
                .andExpect(jsonPath("$.data.channelId").value(org.hamcrest.Matchers.startsWith("test-draft-")));

        NotificationChannelCommand sent = cap.getValue();
        org.assertj.core.api.Assertions.assertThat(sent.id()).startsWith("test-draft-");
        org.assertj.core.api.Assertions.assertThat(sent.type()).isEqualTo(ChannelType.WEBHOOK);
        org.assertj.core.api.Assertions.assertThat(sent.secret()).isEqualTo("sec_abc");
        org.assertj.core.api.Assertions.assertThat(sent.clearSecret()).isFalse();
        org.assertj.core.api.Assertions.assertThat(sent.timeoutMs()).isEqualTo(3000);

        verify(testService, never()).testChannelById(any(), any());
    }

    @Test
    @DisplayName("POST /channels/test 外部 HTTP 5xx → 200 + FAILED 结果,errorMessage 写入")
    void testChannelExternalFailureReturns200WithFailedResult() throws Exception {
        NotificationTestResult result = NotificationTestResult.builder()
                .recordId(UUID.randomUUID().toString())
                .channelId("webhook-default")
                .eventType(NotificationEventType.TEST)
                .status(NotificationStatus.FAILED)
                .responseCode(500)
                .errorMessage("HTTP 500")
                .sentAt(LocalDateTime.of(2026, 6, 19, 10, 0))
                .durationMs(220L)
                .build();
        when(testService.testChannelById(eq("webhook-default"), any())).thenReturn(result);

        String body = """
                {"channelId":"webhook-default"}
                """;

        mockMvc.perform(post("/api/notification/channels/test")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.responseCode").value(500))
                .andExpect(jsonPath("$.data.errorMessage").value("HTTP 500"));
    }

    @Test
    @DisplayName("POST /channels/test 缺 URL/draft 模式 → 400 BadRequest")
    void testChannelDraftMissingUrlReturns400() throws Exception {
        String body = """
                {"type":"WEBHOOK","enabled":true,"message":"测试"}
                """;

        mockMvc.perform(post("/api/notification/channels/test")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("url is required")));

        verify(testService, never()).testChannelDraft(any(), any());
    }

    @Test
    @DisplayName("POST /channels/test 缺 type/draft 模式 → 400 BadRequest")
    void testChannelDraftMissingTypeReturns400() throws Exception {
        String body = """
                {"enabled":true,"url":"https://example.com"}
                """;

        mockMvc.perform(post("/api/notification/channels/test")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("type is required")));
    }

    @Test
    @DisplayName("POST /channels/test service 抛 IllegalArgumentException → 透传为 400")
    void testChannelServiceArgumentException() throws Exception {
        when(testService.testChannelById(eq("missing"), any()))
                .thenThrow(new IllegalArgumentException("channel not found: missing"));

        String body = """
                {"channelId":"missing"}
                """;

        mockMvc.perform(post("/api/notification/channels/test")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("channel not found")));
    }

    // ==================== GET /api/notification/test-records (P1-01 Task 7) ====================

    @Test
    @DisplayName("GET /test-records 默认 limit=20,返回倒序的最近 N 条")
    void recentTestRecordsDefaultLimit() throws Exception {
        NotificationRecord r1 = testRecord("rec-1", "ch-a", NotificationStatus.SENT, 200,
                LocalDateTime.of(2026, 6, 19, 10, 0));
        NotificationRecord r2 = testRecord("rec-2", "ch-b", NotificationStatus.FAILED, 500,
                LocalDateTime.of(2026, 6, 19, 9, 0));
        when(configService.listRecentTestRecords(20)).thenReturn(List.of(r1, r2));

        mockMvc.perform(get("/api/notification/test-records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].recordId").value("rec-1"))
                .andExpect(jsonPath("$.data[0].status").value("SENT"))
                .andExpect(jsonPath("$.data[0].responseCode").value(200))
                .andExpect(jsonPath("$.data[0].channelId").value("ch-a"))
                .andExpect(jsonPath("$.data[1].recordId").value("rec-2"))
                .andExpect(jsonPath("$.data[1].status").value("FAILED"))
                .andExpect(jsonPath("$.data[1].errorMessage").isString());

        // 默认 limit=20
        verify(configService).listRecentTestRecords(20);
    }

    @Test
    @DisplayName("GET /test-records?limit=5 → 透传 5")
    void recentTestRecordsCustomLimit() throws Exception {
        when(configService.listRecentTestRecords(5)).thenReturn(List.of());

        mockMvc.perform(get("/api/notification/test-records").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        verify(configService).listRecentTestRecords(5);
    }

    @Test
    @DisplayName("GET /test-records?limit=0 → clamp 到 1")
    void recentTestRecordsLimitClampLower() throws Exception {
        when(configService.listRecentTestRecords(1)).thenReturn(List.of());

        mockMvc.perform(get("/api/notification/test-records").param("limit", "0"))
                .andExpect(status().isOk());

        verify(configService).listRecentTestRecords(1);
    }

    @Test
    @DisplayName("GET /test-records?limit=999 → clamp 到 20")
    void recentTestRecordsLimitClampUpper() throws Exception {
        when(configService.listRecentTestRecords(20)).thenReturn(List.of());

        mockMvc.perform(get("/api/notification/test-records").param("limit", "999"))
                .andExpect(status().isOk());

        verify(configService).listRecentTestRecords(20);
    }

    @Test
    @DisplayName("GET /test-records 空列表 → 200 + 空数组")
    void recentTestRecordsEmpty() throws Exception {
        when(configService.listRecentTestRecords(20)).thenReturn(List.of());

        mockMvc.perform(get("/api/notification/test-records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ==================== 校验 ====================

    @Test
    @DisplayName("POST /api/notification/channels → URL 为空返回 400")
    void createChannelMissingUrlReturns400() throws Exception {
        String body = """
                {"channelId":"test","type":"WEBHOOK","enabled":true}
                """;

        mockMvc.perform(post("/api/notification/channels")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("POST /api/notification/channels → type 为空返回 400")
    void createChannelMissingTypeReturns400() throws Exception {
        String body = """
                {"channelId":"test","enabled":true,"url":"https://example.com"}
                """;

        mockMvc.perform(post("/api/notification/channels")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("PUT /api/notification/settings → version 为空返回 400")
    void updateSettingsMissingVersionReturns400() throws Exception {
        String body = """
                {"enabled":true,"dryRun":false}
                """;

        mockMvc.perform(put("/api/notification/settings")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    // ==================== 辅助 ====================

    private static NotificationRecord testRecord(String recordId, String channelId,
                                                 NotificationStatus status, int responseCode,
                                                 LocalDateTime sentAt) {
        return NotificationRecord.builder()
                .recordId(recordId)
                .eventId(UUID.randomUUID().toString())
                .auditId(null) // TEST 记录 auditId 必须 null
                .channelId(channelId)
                .channelType(ChannelType.WEBHOOK)
                .status(status)
                .requestPayload("{}")
                .responseCode(responseCode)
                .errorMessage(status == NotificationStatus.FAILED ? "HTTP " + responseCode : null)
                .retryCount(0)
                .createdAt(sentAt.minusSeconds(1))
                .sentAt(sentAt)
                .eventType(NotificationEventType.TEST)
                .build();
    }
}
