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
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * 通用 Webhook 通道 — POST JSON 到 channelConfig.url。
 *
 * <p><b>特性</b>:</p>
 * <ul>
 *   <li>JSON 序列化(用 {@link ObjectMapper} 处理 maskedPayload)</li>
 *   <li>3s 超时(channelConfig.timeoutMs 可覆盖)</li>
 *   <li>若 channelConfig.secret 非空 → 添加 <code>X-KylinOps-Signature: HMAC-SHA256</code> 请求头</li>
 *   <li>所有异常抛回 dispatcher 统一处理(<b>不在此处 catch</b>)</li>
 * </ul>
 */
@Slf4j
@Component
public class WebhookChannel implements NotificationChannel {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String SIGNATURE_HEADER = "X-KylinOps-Signature";
    private static final String HMAC_ALG = "HmacSHA256";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public WebhookChannel(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .writeTimeout(3, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public ChannelType type() {
        return ChannelType.WEBHOOK;
    }

    @Override
    public NotificationSendResult send(NotificationEvent event, String maskedPayload,
                                       NotificationConfig.ChannelConfig channelConfig) {
        if (channelConfig.getUrl() == null || channelConfig.getUrl().isBlank()) {
            return NotificationSendResult.exception("channel URL 未配置");
        }
        int timeoutMs = channelConfig.effectiveTimeoutMs();
        OkHttpClient client = httpClient.newBuilder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build();

        Request.Builder reqBuilder = new Request.Builder()
                .url(channelConfig.getUrl())
                .post(RequestBody.create(maskedPayload, JSON));

        String secret = channelConfig.getSecret();
        if (secret != null && !secret.isBlank()) {
            reqBuilder.header(SIGNATURE_HEADER, "HMAC-SHA256 " + hmacSha256(secret, maskedPayload));
        }

        try (Response response = client.newCall(reqBuilder.build()).execute()) {
            ResponseBody body = response.body();
            String bodyStr = body == null ? "" : truncate(body.string(), 1024);
            int code = response.code();
            if (response.isSuccessful()) {
                return NotificationSendResult.ok(code, bodyStr);
            }
            return NotificationSendResult.fail(code, bodyStr, "HTTP " + code);
        } catch (IOException e) {
            log.error("Webhook 发送异常: channelId={}, url={}",
                    channelConfig.getId(), channelConfig.getUrl(), e);
            throw new RuntimeException("Webhook 发送失败: " + e.getMessage(), e);
        }
    }

    static String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALG));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("HMAC 计算失败: " + e.getMessage(), e);
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
