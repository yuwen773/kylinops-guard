package com.kylinops.notification.config;

import com.kylinops.notification.NotificationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
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

    private static final Logger log = LoggerFactory.getLogger(NotificationManagementBeansConfig.class);

    @Bean
    public NotificationSecretCipher notificationSecretCipher(
            NotificationManagementProperties properties) {
        return new NotificationSecretCipher(properties.masterKey());
    }

    /**
     * 启动期初始化:把 YAML 发送平面配置导入管理表 + 发布首版快照。
     *
     * <p>使用 {@code ApplicationRunner} (上下文完整 refresh 后执行,确保
     * {@code @Transactional} 等 AOP 代理生效)。</p>
     *
     * <p>通过 {@link ObjectProvider} 注入 {@link NotificationConfigurationService}
     * 和 {@link NotificationConfig},使该 runner 在切片测试中不存在目标 bean 时
     * 自动跳过,避免启动失败。</p>
     */
    @Bean
    public ApplicationRunner notificationConfigurationInitializer(
            ObjectProvider<NotificationConfigurationService> configServiceProvider,
            ObjectProvider<NotificationConfig> yamlProvider) {
        return (ApplicationArguments args) -> {
            NotificationConfigurationService configurationService = configServiceProvider.getIfAvailable();
            if (configurationService == null) {
                log.debug("NotificationConfigurationService 不可用,跳过启动期初始化");
                return;
            }
            NotificationConfig yaml = yamlProvider.getIfAvailable();
            try {
                configurationService.initialize(yaml);
                log.info("通知中心启动期初始化完成");
            } catch (Exception e) {
                log.error("通知中心启动期初始化失败 — 应用终止", e);
                throw e;
            }
        };
    }
}