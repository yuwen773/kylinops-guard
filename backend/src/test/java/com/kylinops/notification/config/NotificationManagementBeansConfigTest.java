package com.kylinops.notification.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NotificationManagementBeansConfig} 与 {@link NotificationManagementProperties}
 * 绑定测试。
 *
 * <p>覆盖:</p>
 * <ul>
 *   <li>合法 Base64 32 字节密钥 → {@link NotificationSecretCipher} Bean 正常工作</li>
 *   <li>非法 Base64 → 上下文启动失败,异常消息<b>不包含</b>配置的密钥</li>
 *   <li>非法长度密钥 → 上下文启动失败,异常消息<b>不包含</b>配置的密钥</li>
 * </ul>
 *
 * <p>失败场景使用 {@link org.springframework.boot.test.context.runner.ApplicationContextRunner}
 * (轻量级,无副作用),成功场景用 {@code @SpringBootTest} 验证 YAML 绑定。</p>
 */
class NotificationManagementBeansConfigTest {

    private static String randomBase64Key(int bytes) {
        byte[] raw = new byte[bytes];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @DisplayName("test profile 默认主密钥 — Bean 绑定并能加解密")
    class WithValidTestKey {

        @org.springframework.beans.factory.annotation.Autowired
        private ApplicationContext context;

        @Test
        void cipherBeanIsRegisteredAndUsable() {
            assertThat(context.containsBean("notificationSecretCipher")).isTrue();
            NotificationSecretCipher cipher =
                    context.getBean(NotificationSecretCipher.class);
            assertThat(cipher).isNotNull();
            assertThat(cipher.isConfigured()).isTrue();

            String encrypted = cipher.encrypt("hello");
            assertThat(encrypted).startsWith("v1:");
            assertThat(cipher.decrypt(encrypted)).isEqualTo("hello");
        }

        @Test
        void propertiesAreBoundFromYaml() {
            NotificationManagementProperties props =
                    context.getBean(NotificationManagementProperties.class);
            assertThat(props.masterKey())
                    .as("test profile 默认主密钥应从 yml 绑定")
                    .isNotBlank();
            assertThat(props.publicBaseUrl())
                    .as("test profile 默认 publicBaseUrl 应从 yml 绑定")
                    .isEqualTo("http://localhost:8080");
        }
    }

    @Nested
    @DisplayName("非法 Base64 主密钥 — 上下文启动失败,异常消息不含密钥")
    class InvalidBase64KeyFailsContext {

        @Test
        void contextStartupFailsAndMessageDoesNotLeakKey() {
            String badKey = "!!!not-base64!!!";
            new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                    .withUserConfiguration(TestConfig.class)
                    .withPropertyValues(
                            "kylinops.notification.management.master-key=" + badKey,
                            "kylinops.notification.management.public-base-url=http://x")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .rootCause()
                                .hasMessageNotContaining(badKey)
                                .hasMessageContaining("Base64");
                    });
        }
    }

    @Nested
    @DisplayName("合法 Base64 但非 32 字节 — 上下文启动失败,异常消息不含密钥")
    class InvalidKeyLengthFailsContext {

        @Test
        void contextStartupFailsAndMessageDoesNotLeakKey() {
            String tooShort = randomBase64Key(16); // AES-128 长度 — 业务要求 32 字节
            new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                    .withUserConfiguration(TestConfig.class)
                    .withPropertyValues(
                            "kylinops.notification.management.master-key=" + tooShort,
                            "kylinops.notification.management.public-base-url=http://x")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .rootCause()
                                .hasMessageNotContaining(tooShort)
                                .hasMessageContaining("32");
                    });
        }
    }

    /**
     * 最小化配置 — 仅启用 NotificationManagementProperties + 注册 cipher Bean。
     * 避免拖入整个 Spring Boot 上下文。
     *
     * <p>注:使用 {@code @Configuration} 而非 {@code @SpringBootConfiguration},避免 {@code @DataJpaTest}
     * 把它当作 boot config 而找不到 {@code @EnableAutoConfiguration} 基础包。</p>
     */
    @org.springframework.context.annotation.Configuration
    @EnableConfigurationProperties(NotificationManagementProperties.class)
    @Import(NotificationManagementBeansConfig.class)
    static class TestConfig {
    }
}