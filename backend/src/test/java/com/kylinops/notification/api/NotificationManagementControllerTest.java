package com.kylinops.notification.api;

import com.kylinops.notification.ChannelType;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
 * <p>验证管理 API 的认证、校验、正常流程和乐观锁 409 冲突处理。</p>
 */
@WebMvcTest(NotificationManagementController.class)
@WithMockUser
@DisplayName("NotificationManagementController — 通知配置管理 API")
class NotificationManagementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NotificationConfigurationService configService;

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
    @DisplayName("GET /api/notification/settings → 200 + 设置 + 全量通道列表（不含 secret）")
    void getSettingsReturnsSettingsWithChannels() throws Exception {
        when(configService.getSettings()).thenReturn(settingsModel);
        when(configService.listChannels()).thenReturn(List.of(webhookChannel, feishuChannel));

        mockMvc.perform(get("/api/notification/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.dryRun").value(false))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.channels").isArray())
                .andExpect(jsonPath("$.data.channels.length()").value(2))
                // 第一个通道：无 secret，secretConfigured=false
                .andExpect(jsonPath("$.data.channels[0].id").value("webhook-default"))
                .andExpect(jsonPath("$.data.channels[0].type").value("WEBHOOK"))
                .andExpect(jsonPath("$.data.channels[0].secretConfigured").value(false))
                .andExpect(jsonPath("$.data.channels[0].secret").doesNotExist())
                .andExpect(jsonPath("$.data.channels[0].encryptedSecret").doesNotExist())
                // 第二个通道：有 secret，secretConfigured=true
                .andExpect(jsonPath("$.data.channels[1].id").value("feishu-prod"))
                .andExpect(jsonPath("$.data.channels[1].secretConfigured").value(true))
                .andExpect(jsonPath("$.data.channels[1].secret").doesNotExist())
                .andExpect(jsonPath("$.data.channels[1].encryptedSecret").doesNotExist());
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
        // buildSettingsView() calls getSettings() + listChannels() internally
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
}
