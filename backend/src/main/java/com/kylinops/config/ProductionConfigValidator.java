package com.kylinops.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 生产环境配置校验器 — 只在 prod profile 下激活。
 * <p>
 * 在 {@link InitializingBean#afterPropertiesSet()} 中校验必需配置项是否已设置，
 * 缺失时抛出 {@link IllegalStateException} 阻止应用启动，实现 fail-closed。
 * </p>
 *
 * <h3>校验项</h3>
 * <ul>
 *   <li>数据库 URL / 用户名 / 密码</li>
 *   <li>管理员 BCrypt 密码哈希（kylinops.admin.password-hash）</li>
 *   <li>LLM 启用时：base-url / api-key / model</li>
 * </ul>
 */
@Slf4j
@Component
@Profile("prod")
public class ProductionConfigValidator implements InitializingBean {

    @Value("${spring.datasource.url:}")
    private String dbUrl;

    @Value("${spring.datasource.username:}")
    private String dbUsername;

    @Value("${spring.datasource.password:}")
    private String dbPassword;

    @Value("${kylinops.admin.password-hash:}")
    private String adminPasswordHash;

    @Value("${kylinops.llm.enabled:false}")
    private boolean llmEnabled;

    @Value("${kylinops.llm.base-url:}")
    private String llmBaseUrl;

    @Value("${kylinops.llm.api-key:}")
    private String llmApiKey;

    @Value("${kylinops.llm.model:}")
    private String llmModel;

    @Override
    public void afterPropertiesSet() {
        log.info("ProductionConfigValidator: 生产配置校验开始");

        validateRequired("spring.datasource.url", dbUrl, "数据库 JDBC URL");
        validateRequired("spring.datasource.username", dbUsername, "数据库用户名");
        validateRequired("spring.datasource.password", dbPassword, "数据库密码");
        validateRequired("kylinops.admin.password-hash", adminPasswordHash, "管理员 BCrypt 密码哈希");

        if (llmEnabled) {
            validateRequired("kylinops.llm.base-url", llmBaseUrl, "LLM API Base URL");
            validateRequired("kylinops.llm.api-key", llmApiKey, "LLM API Key");
            validateRequired("kylinops.llm.model", llmModel, "LLM 模型名");
        }

        log.info("ProductionConfigValidator: 生产配置校验通过");
    }

    private void validateRequired(String propertyKey, String value, String displayName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "生产环境必需配置缺失: " + propertyKey + " (" + displayName + ")。" +
                            "请通过环境变量或配置文件设置此值，然后重新启动。"
            );
        }
    }
}
