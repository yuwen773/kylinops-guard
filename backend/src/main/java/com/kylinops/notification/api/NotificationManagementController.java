package com.kylinops.notification.api;

import com.kylinops.common.ApiResponse;
import com.kylinops.common.BusinessException;
import com.kylinops.notification.ChannelType;
import com.kylinops.notification.NotificationTestResult;
import com.kylinops.notification.NotificationTestService;
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
 * <p>提供运行时配置的 CRUD + 查询 + 连接测试接口。</p>
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
    private static final int MIN_TEST_RECORDS_LIMIT = 1;
    private static final int MAX_TEST_RECORDS_LIMIT = 20;
    private static final int DEFAULT_TEST_RECORDS_LIMIT = 20;

    private final NotificationConfigurationService configService;
    private final NotificationTestService testService;

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
    // 连接测试 (P1-01 Task 7)
    // ============================================================

    /**
     * 触发一次通道连接测试。
     *
     * <p>两种模式(由 {@link NotificationChannelTestRequest#isSavedMode()} 判定):</p>
     * <ul>
     *   <li>已保存:只填 {@code channelId},从数据库解析存储配置</li>
     *   <li>draft:不填 {@code channelId},直接用 body 中的 url/secret/timeoutMs</li>
     * </ul>
     *
     * <p>外部 HTTP 失败也返回 200(FAILED 体现在 result.status)。</p>
     */
    @PostMapping("/channels/test")
    public ApiResponse<NotificationTestResult> testChannel(
            @Valid @RequestBody NotificationChannelTestRequest request) {
        try {
            if (request.isSavedMode()) {
                return ApiResponse.success(
                        testService.testChannelById(request.channelId(), request.message()));
            }
            // draft 模式:必填 type / url
            if (request.type() == null) {
                throw BusinessException.badRequest("type is required for draft test");
            }
            if (request.url() == null || request.url().isBlank()) {
                throw BusinessException.badRequest("url is required for draft test");
            }
            String draftId = "test-draft-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            NotificationChannelCommand command = NotificationChannelCommand.builder()
                    .id(draftId)
                    .type(request.type())
                    .enabled(request.enabledOrDefault())
                    .url(request.url())
                    .secret(request.secret())
                    .clearSecret(request.clearSecretOrDefault())
                    .timeoutMs(request.timeoutOrDefault())
                    .build();
            return ApiResponse.success(testService.testChannelDraft(command, request.message()));
        } catch (IllegalArgumentException e) {
            // resolveForTest / draft 校验失败 → 400 BadRequest
            throw BusinessException.badRequest(e.getMessage());
        }
    }

    /**
     * 查询最近 N 条 TEST 记录(管理端「最近测试记录」面板)。
     *
     * <p>limit 强制 clamp 到 [1, 20];默认 20。</p>
     */
    @GetMapping("/test-records")
    public ApiResponse<List<NotificationTestRecordSummary>> recentTestRecords(
            @RequestParam(required = false) Integer limit) {
        int effective = clampLimit(limit);
        List<NotificationTestRecordSummary> records = configService.listRecentTestRecords(effective)
                .stream()
                .map(record -> NotificationTestRecordSummary.from(record, computeDurationMs(record)))
                .toList();
        return ApiResponse.success(records);
    }

    private static int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_TEST_RECORDS_LIMIT;
        }
        return Math.max(MIN_TEST_RECORDS_LIMIT, Math.min(limit, MAX_TEST_RECORDS_LIMIT));
    }

    private static long computeDurationMs(com.kylinops.notification.NotificationRecord record) {
        if (record.getSentAt() == null || record.getCreatedAt() == null) {
            return 0L;
        }
        long ms = java.time.Duration.between(record.getCreatedAt(), record.getSentAt()).toMillis();
        return Math.max(0L, ms);
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
                .lastTestResult(model.lastTestResult())
                .build();
    }
}
