package com.kylinops.notification.api;

import com.kylinops.common.ApiResponse;
import com.kylinops.common.BusinessException;
import com.kylinops.notification.config.NotificationChannelCommand;
import com.kylinops.notification.config.NotificationChannelModel;
import com.kylinops.notification.config.NotificationConfigurationConflictException;
import com.kylinops.notification.config.NotificationConfigurationService;
import com.kylinops.notification.config.NotificationSettingsCommand;
import com.kylinops.notification.config.NotificationSettingsModel;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 通知配置管理 REST 控制器。
 *
 * <p>提供运行时配置的 CRUD + 查询接口。</p>
 *
 * <h3>安全约束</h3>
 * <ul>
 *   <li>不记录包含 secret 的 request DTO（{@link NotificationChannelUpsertRequest#secret()}）</li>
 *   <li>channel 响应永远不反回 secret 值，仅暴露 {@code secretConfigured} 布尔状态</li>
 *   <li>所有写操作需要 CSRF token（Spring Security 默认）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/notification")
@RequiredArgsConstructor
public class NotificationManagementController {

    private static final int DEFAULT_TIMEOUT_MS = 3000;

    private final NotificationConfigurationService configService;

    /**
     * 获取通知设置 + 全量通道列表。
     */
    @GetMapping("/settings")
    public ApiResponse<NotificationSettingsView> getSettings() {
        return ApiResponse.success(buildSettingsView());
    }

    /**
     * 更新通知设置并返回完整视图（含通道列表）。
     */
    @PutMapping("/settings")
    public ApiResponse<NotificationSettingsView> updateSettings(
            @Valid @RequestBody NotificationSettingsUpdateRequest request) {
        NotificationSettingsCommand command = NotificationSettingsCommand.builder()
                .enabled(request.enabled())
                .dryRun(request.dryRun())
                .version(request.version())
                .build();

        try {
            configService.updateSettings(command);
        } catch (NotificationConfigurationConflictException e) {
            throw BusinessException.conflict(e.getMessage());
        }

        return ApiResponse.success(buildSettingsView());
    }

    /**
     * 构建完整设置视图（设置 + 全量通道列表）。
     */
    private NotificationSettingsView buildSettingsView() {
        NotificationSettingsModel settings = configService.getSettings();
        List<NotificationChannelView> channels = configService.listChannels()
                .stream()
                .map(this::toView)
                .toList();
        return NotificationSettingsView.builder()
                .enabled(settings.enabled())
                .dryRun(settings.dryRun())
                .version(settings.version())
                .channels(channels)
                .build();
    }

    /**
     * 创建新通知通道。
     */
    @PostMapping("/channels")
    public ApiResponse<NotificationChannelView> createChannel(
            @Valid @RequestBody NotificationChannelUpsertRequest request) {
        if (request.channelId() == null || request.channelId().isBlank()) {
            throw BusinessException.badRequest("channelId is required");
        }

        NotificationChannelCommand command = NotificationChannelCommand.builder()
                .id(request.channelId())
                .type(request.type())
                .enabled(request.enabled())
                .url(request.url())
                .secret(request.secret())
                .clearSecret(request.clearSecret())
                .timeoutMs(request.timeoutMs() != null ? request.timeoutMs() : DEFAULT_TIMEOUT_MS)
                .version(0L)
                .build();

        NotificationChannelModel model = configService.createChannel(command);
        return ApiResponse.success(toView(model));
    }

    /**
     * 更新已存在通知通道。
     */
    @PutMapping("/channels/{channelId}")
    public ApiResponse<NotificationChannelView> updateChannel(
            @PathVariable String channelId,
            @Valid @RequestBody NotificationChannelUpsertRequest request) {
        if (request.version() == null) {
            throw BusinessException.badRequest("version is required for update");
        }

        NotificationChannelCommand command = NotificationChannelCommand.builder()
                .id(channelId)
                .type(request.type())
                .enabled(request.enabled())
                .url(request.url())
                .secret(request.secret())
                .clearSecret(request.clearSecret())
                .timeoutMs(request.timeoutMs() != null ? request.timeoutMs() : DEFAULT_TIMEOUT_MS)
                .version(request.version())
                .build();

        NotificationChannelModel model = configService.updateChannel(channelId, command);
        return ApiResponse.success(toView(model));
    }

    /**
     * 软删除通知通道。
     */
    @DeleteMapping("/channels/{channelId}")
    public ApiResponse<Void> deleteChannel(
            @PathVariable String channelId,
            @RequestParam long version) {
        configService.deleteChannel(channelId, version);
        return ApiResponse.success();
    }

    // ============================================================
    // 视图转换
    // ============================================================

    private NotificationChannelView toView(NotificationChannelModel model) {
        return NotificationChannelView.builder()
                .id(model.id())
                .type(model.type())
                .enabled(model.enabled())
                .url(model.url())
                .secretConfigured(model.hasSecret())
                .timeoutMs(model.timeoutMs())
                .version(model.version())
                .createdAt(model.createdAt())
                .updatedAt(model.updatedAt())
                .build();
    }
}
