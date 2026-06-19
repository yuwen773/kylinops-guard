package com.kylinops.notification.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kylinops.notification.ChannelType;
import com.kylinops.notification.NotificationChannel;
import com.kylinops.notification.NotificationConfig;
import com.kylinops.notification.NotificationEvent;
import com.kylinops.notification.NotificationEventType;
import com.kylinops.notification.NotificationPublicLinkProperties;
import com.kylinops.notification.NotificationSendResult;
import com.kylinops.notification.NotificationSeverity;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 飞书机器人 Webhook 通道。
 *
 * <p><b>签名算法(飞书官方)</b>:</p>
 * <pre>
 *   string_to_sign = timestamp + "\n" + secret
 *   hmac_code      = hmac_sha256(string_to_sign, secret)
 *   sign           = base64(hmac_code)
 * </pre>
 *
 * <p><b>Payload 格式</b>:interactive 卡片(Task 8 精简版) — 严重等级 header 模板 + 短标题
 * + 事件/时间双列 + summary + 1 个 key object + 审计 ID + 可选"查看审计详情"按钮。
 * 详情/命令原文/detail 字段一律不进卡片。</p>
 */
@Slf4j
@Component
public class FeishuChannel implements NotificationChannel {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String HMAC_ALG = "HmacSHA256";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final NotificationPublicLinkProperties publicLink;

    public FeishuChannel(ObjectMapper objectMapper, NotificationPublicLinkProperties publicLink) {
        this.objectMapper = objectMapper;
        this.publicLink = publicLink;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .writeTimeout(3, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public ChannelType type() {
        return ChannelType.FEISHU;
    }

    @Override
    public NotificationSendResult send(NotificationEvent event, String maskedPayload,
                                       NotificationConfig.ChannelConfig channelConfig) {
        if (channelConfig.getUrl() == null || channelConfig.getUrl().isBlank()) {
            return NotificationSendResult.exception("飞书 webhook URL 未配置");
        }
        String secret = channelConfig.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("飞书通道 secret 必填(channelId=" + channelConfig.getId() + ")");
        }

        long timestamp = System.currentTimeMillis() / 1000L;
        String sign = generateSign(secret, timestamp);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", String.valueOf(timestamp));
        payload.put("sign", sign);
        payload.put("msg_type", "interactive");
        payload.put("card", buildCard(event));

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("飞书 payload 序列化失败: " + e.getMessage(), e);
        }

        int timeoutMs = channelConfig.effectiveTimeoutMs();
        OkHttpClient client = httpClient.newBuilder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build();

        Request request = new Request.Builder()
                .url(channelConfig.getUrl())
                .post(RequestBody.create(json, JSON))
                .build();

        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            String bodyStr = body == null ? "" : truncate(body.string(), 1024);
            int code = response.code();
            if (response.isSuccessful()) {
                return NotificationSendResult.ok(code, bodyStr);
            }
            return NotificationSendResult.fail(code, bodyStr, "HTTP " + code);
        } catch (IOException e) {
            log.error("飞书发送异常: channelId={}, url={}",
                    channelConfig.getId(), channelConfig.getUrl(), e);
            throw new RuntimeException("飞书发送失败: " + e.getMessage(), e);
        }
    }

    /**
     * 飞书签名:string_to_sign = timestamp + "\n" + secret → hmac_sha256 → base64。
     */
    static String generateSign(String secret, long timestamp) {
        try {
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(stringToSign.getBytes(StandardCharsets.UTF_8), HMAC_ALG));
            byte[] hmacCode = mac.doFinal(new byte[]{});
            return Base64.getEncoder().encodeToString(hmacCode);
        } catch (Exception e) {
            throw new RuntimeException("飞书签名计算失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构造飞书消息卡片 — 精简 interactive 卡片,适配手机端查看。
     *
     * <p>结构:</p>
     * <ol>
     *   <li>header:严重等级模板 + 短标题(由 eventType + severity 决定)</li>
     *   <li>div(双列):事件标签 + 发生时间</li>
     *   <li>div:summary(如存在且非 TEST)</li>
     *   <li>div:1 个 key object(matchedRuleId / serviceName / diskPath / promptInjectionPattern)</li>
     *   <li>div:审计 ID(auditId 存在且非 TEST)</li>
     *   <li>action:查看审计详情按钮(publicBaseUrl 有效且非 TEST)</li>
     * </ol>
     *
     * <p><b>不变量</b>:不输出 {@code event.detail} 与旧版长标题
     * {@code event.title} — detail 可能含敏感命令原文,长标题在手机端拥挤。</p>
     */
    private Map<String, Object> buildCard(NotificationEvent event) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("config", Map.of("wide_screen_mode", true));

        // ── 1. header ─────────────────────────────────────────────────────
        boolean isTest = event.getEventType() == NotificationEventType.TEST;
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("template", pickHeaderTemplate(event));
        header.put("title", Map.of(
                "tag", "plain_text",
                "content", pickShortTitle(event)));
        card.put("header", header);

        List<Map<String, Object>> elements = new ArrayList<>();

        // ── 2. 事件 + 时间(双列) ──────────────────────────────────────────
        elements.add(Map.of(
                "tag", "div",
                "fields", List.of(
                        Map.of("is_short", true, "text", Map.of(
                                "tag", "lark_md",
                                "content", "**事件**\n" + eventTypeLabel(event.getEventType()))),
                        Map.of("is_short", true, "text", Map.of(
                                "tag", "lark_md",
                                "content", "**时间**\n" + formatOccurredAt(event.getOccurredAt()))))));

        // ── 3. summary(非 TEST) ──────────────────────────────────────────
        if (!isTest && event.getSummary() != null && !event.getSummary().isBlank()) {
            elements.add(Map.of(
                    "tag", "div",
                    "text", Map.of("tag", "lark_md", "content", safe(event.getSummary()))));
        }

        // ── 4. 1 个 key object(命中规则 / 注入模式 / 服务 / 磁盘) ───────
        String keyObject = pickKeyObject(event);
        if (keyObject != null) {
            elements.add(Map.of(
                    "tag", "div",
                    "text", Map.of("tag", "lark_md", "content", keyObject)));
        }

        // ── 5. 审计 ID(非 TEST 且 auditId 存在) ────────────────────────
        if (!isTest && event.getAuditId() != null && !event.getAuditId().isBlank()) {
            elements.add(Map.of(
                    "tag", "div",
                    "text", Map.of("tag", "lark_md",
                            "content", "**审计**\n`" + safe(event.getAuditId()) + "`")));
        }

        // ── 6. 查看审计详情 按钮(publicBaseUrl 有效且非 TEST) ──────────
        if (!isTest) {
            Optional<String> auditUrl = publicLink.auditUrl(event.getAuditId());
            if (auditUrl.isPresent()) {
                String auditId = event.getAuditId();
                elements.add(Map.of(
                        "tag", "action",
                        "actions", List.of(Map.of(
                                "tag", "button",
                                "type", "primary",
                                "text", Map.of("tag", "plain_text", "content", "查看审计详情"),
                                "url", auditUrl.get(),
                                "value", Map.of("action", "view-audit", "auditId", auditId)))));
            }
        }

        card.put("elements", elements.toArray());
        return card;
    }

    // ── buildCard helpers ─────────────────────────────────────────────

    private static String pickHeaderTemplate(NotificationEvent event) {
        // TEST 强制 blue(便于在群里和管理员测试事件视觉区分)
        if (event.getEventType() == NotificationEventType.TEST) {
            return "blue";
        }
        NotificationSeverity sev = event.getSeverity();
        if (sev == null) {
            return "grey";
        }
        return switch (sev) {
            case CRITICAL -> "red";
            case WARNING -> "orange";
            case INFO -> "blue";
        };
    }

    private static String pickShortTitle(NotificationEvent event) {
        if (event.getEventType() == null) {
            return "通知事件";
        }
        return switch (event.getEventType()) {
            case L4_BLOCK -> "高风险操作已阻断";
            case PROMPT_INJECTION_BLOCK -> "Prompt 注入拦截";
            case L2_CONFIRM_REQUIRED -> "待确认操作";
            case SERVICE_ABNORMAL -> "服务异常";
            case DISK_RISK -> "磁盘风险";
            case TEST -> "通道连接测试";
        };
    }

    private static String eventTypeLabel(NotificationEventType type) {
        if (type == null) {
            return "—";
        }
        return switch (type) {
            case L4_BLOCK -> "L4 阻断";
            case PROMPT_INJECTION_BLOCK -> "Prompt 注入";
            case L2_CONFIRM_REQUIRED -> "L2 待确认";
            case SERVICE_ABNORMAL -> "服务异常";
            case DISK_RISK -> "磁盘风险";
            case TEST -> "通道测试";
        };
    }

    private static final DateTimeFormatter OCCURRED_AT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static String formatOccurredAt(LocalDateTime ts) {
        return ts == null ? "—" : OCCURRED_AT_FMT.format(ts);
    }

    /**
     * 选 1 个 key object:注入模式 → 命中规则 → 服务 → 磁盘(路径 + 使用率)。
     * 注入模式比命中规则更具体(模式名 vs 通用规则),优先显示。
     */
    private static String pickKeyObject(NotificationEvent event) {
        if (event.getPromptInjectionPattern() != null && !event.getPromptInjectionPattern().isBlank()) {
            return "**注入模式**\n`" + safe(event.getPromptInjectionPattern()) + "`";
        }
        if (event.getMatchedRuleId() != null && !event.getMatchedRuleId().isBlank()) {
            return "**命中规则**\n`" + safe(event.getMatchedRuleId()) + "`";
        }
        if (event.getServiceName() != null && !event.getServiceName().isBlank()) {
            return "**服务**\n`" + safe(event.getServiceName()) + "`";
        }
        if (event.getDiskPath() != null && !event.getDiskPath().isBlank()) {
            StringBuilder sb = new StringBuilder("**磁盘**\n`")
                    .append(safe(event.getDiskPath())).append("`");
            if (event.getDiskUsagePercent() != null) {
                sb.append(" (").append(String.format("%.1f%%", event.getDiskUsagePercent()))
                        .append(")");
            }
            return sb.toString();
        }
        return null;
    }

    private static String safe(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\");
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
