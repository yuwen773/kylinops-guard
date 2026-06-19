package com.kylinops.notification;

import com.kylinops.notification.config.NotificationManagementProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 通知中心公网链接拼接器 — P1-01 Plan 01 Task 8。
 *
 * <p>把 {@link NotificationManagementProperties#publicBaseUrl()} 包成
 * 校验后的拼接基址,供飞书卡片在事件携带 auditId 时生成"查看审计详情"按钮 URL。
 * 缺/非法时不报错(返回 empty),启动期 warn 一次(不输出实际值)。</p>
 *
 * <p><b>不变量</b>(CLAUDE.md / Plan):</p>
 * <ul>
 *   <li>仅接受 {@code http(s)://host...} 绝对 URL(无 userinfo)</li>
 *   <li>非法/缺失 → {@link Optional#empty()} + 启动期 warn 一次</li>
 *   <li>不记录、不回显实际 URL 值(可能含敏感前缀)</li>
 * </ul>
 */
@Component
public class NotificationPublicLinkProperties {

    private static final Logger log = LoggerFactory.getLogger(NotificationPublicLinkProperties.class);

    /** null 表示缺/非法 — 不暴露真实值,仅在 warn 中提及"未配置"事实。 */
    private final String baseUrl;

    @Autowired
    public NotificationPublicLinkProperties(NotificationManagementProperties management) {
        this(management == null ? null : management.publicBaseUrl());
    }

    /** 测试入口:从原始字符串构造,便于覆盖"缺/非法/有效"三态。 */
    public NotificationPublicLinkProperties(String rawBaseUrl) {
        this.baseUrl = validate(rawBaseUrl);
    }

    @PostConstruct
    void warnIfInvalid() {
        if (baseUrl == null) {
            // 不输出实际值 — URL 可能含敏感前缀(token 等)
            log.warn("publicBaseUrl is not configured or invalid; "
                    + "audit links will be omitted from notification cards");
        }
    }

    /**
     * 严格校验:必须是 {@code http(s)://host...} 绝对 URL,无 userinfo。
     * 任何不符合 → 返回 null(调用方会得到 Optional.empty())。
     */
    private static String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || host == null || host.isBlank()
                || uri.getUserInfo() != null) {
            return null;
        }
        // 去除尾部斜杠,避免拼接时出现 //audit
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    public Optional<String> url(String pathAndQuery) {
        if (baseUrl == null) {
            return Optional.empty();
        }
        if (pathAndQuery == null || pathAndQuery.isEmpty()) {
            return Optional.of(baseUrl);
        }
        String suffix = pathAndQuery.startsWith("/") ? pathAndQuery : "/" + pathAndQuery;
        return Optional.of(baseUrl + suffix);
    }

    public Optional<String> auditUrl(String auditId) {
        if (auditId == null || auditId.isBlank()) {
            return Optional.empty();
        }
        return url("/audit?auditId=" + URLEncoder.encode(auditId, StandardCharsets.UTF_8));
    }
}
