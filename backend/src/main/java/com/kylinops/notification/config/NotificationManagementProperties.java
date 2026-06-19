package com.kylinops.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 通知中心管理平面配置(<code>kylinops.notification.management.*</code>)。
 *
 * <p>本配置与 {@code kylinops.notification.*} (发送平面, 现有 {@code NotificationConfig})
 * 解耦,避免改动现有导入 DTO。命名空间独立以承接后续管理 API 需要的字段
 * (如 {@code publicBaseUrl})。</p>
 *
 * <h3>配置项</h3>
 * <ul>
 *   <li>{@code masterKey} — Base64 编码 32 字节主密钥,用于信封加密</li>
 *   <li>{@code publicBaseUrl} — 公网回调/链接拼接基址(后续 Task 5 使用)</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "kylinops.notification.management")
public record NotificationManagementProperties(
        String masterKey,
        String publicBaseUrl) {

    public NotificationManagementProperties {
        if (masterKey == null) {
            masterKey = "";
        }
        if (publicBaseUrl == null) {
            publicBaseUrl = "";
        }
    }
}