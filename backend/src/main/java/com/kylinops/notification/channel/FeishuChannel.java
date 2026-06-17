package com.kylinops.notification.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kylinops.notification.ChannelType;
import com.kylinops.notification.NotificationChannel;
import com.kylinops.notification.NotificationConfig;
import com.kylinops.notification.NotificationEvent;
import com.kylinops.notification.NotificationSendResult;
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
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * <p><b>Payload 格式</b>:markdown 消息(与项目 HybridResponseService 输出风格一致)。</p>
 */
@Slf4j
@Component
public class FeishuChannel implements NotificationChannel {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String HMAC_ALG = "HmacSHA256";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public FeishuChannel(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
     * 构造飞书消息卡片 — markdown 风格,与项目 buildDiscussionAnswer 输出风格一致。
     */
    private Map<String, Object> buildCard(NotificationEvent event) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("config", Map.of("wide_screen_mode", true));

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("title", Map.of("tag", "plain_text",
                "content", "[" + severityText(event) + "] " + safe(event.getTitle())));
        card.put("header", header);

        StringBuilder md = new StringBuilder();
        md.append("**事件类型**:`").append(safe(event.getEventType() != null ? event.getEventType().name() : "")).append("`\n");
        if (event.getSummary() != null) {
            md.append("**摘要**:`").append(safe(event.getSummary())).append("`\n");
        }
        if (event.getMatchedRuleId() != null) {
            md.append("**命中规则**:`").append(safe(event.getMatchedRuleId())).append("`\n");
        }
        if (event.getPromptInjectionPattern() != null) {
            md.append("**注入模式**:`").append(safe(event.getPromptInjectionPattern())).append("`\n");
        }
        if (event.getServiceName() != null) {
            md.append("**服务**:`").append(safe(event.getServiceName())).append("`\n");
        }
        if (event.getDiskPath() != null) {
            md.append("**磁盘**:`").append(safe(event.getDiskPath())).append("`\n");
        }
        if (event.getDiskUsagePercent() != null) {
            md.append("**使用率**:`").append(String.format("%.1f%%", event.getDiskUsagePercent())).append("`\n");
        }
        if (event.getDetail() != null) {
            md.append("\n").append(safe(event.getDetail()));
        }

        Map<String, Object> element = new LinkedHashMap<>();
        element.put("tag", "markdown");
        element.put("content", md.toString());
        card.put("elements", new Object[]{element});
        return card;
    }

    private static String severityText(NotificationEvent event) {
        if (event == null || event.getSeverity() == null) return "INFO";
        return event.getSeverity().name();
    }

    private static String safe(String s) {
        if (s == null) return "";
        // 飞书 markdown 模板字符转义
        return s.replace("\\", "\\\\");
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
