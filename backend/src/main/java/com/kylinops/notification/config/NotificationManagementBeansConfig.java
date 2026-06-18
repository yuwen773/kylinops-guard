package com.kylinops.notification.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通知管理平面配套 Bean。
 *
 * <p>仅注册 {@link NotificationManagementProperties} 绑定与
 * {@link NotificationSecretCipher} 单例。<b>不</b>触碰现有发送平面
 * (即 {@code NotificationConfig}) 的 {@code @ConfigurationProperties} 导入,
 * 以避免在 P1-01 实施期内改动现有 YAML 结构。</p>
 */
@Configuration
@EnableConfigurationProperties(NotificationManagementProperties.class)
public class NotificationManagementBeansConfig {

    @Bean
    public NotificationSecretCipher notificationSecretCipher(
            NotificationManagementProperties properties) {
        return new NotificationSecretCipher(properties.masterKey());
    }
}