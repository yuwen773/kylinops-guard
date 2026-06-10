package com.kylinops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 应用自定义配置映射
 * <p>
 * 对应 application.yml 中 kylinops.* 配置项。
 * 后续 Agent、Security、Audit 模块通过此 Bean 读取配置。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "kylinops")
public class KylinOpsConfig {

    /** 应用版本号 */
    private String version;

    /** 应用信息 */
    private App app = new App();

    /** LLM 配置 */
    private Llm llm = new Llm();

    /** 安全配置 */
    private Security security = new Security();

    /** 审计配置 */
    private Audit audit = new Audit();

    @Data
    public static class App {
        private String name;
        private String codename;
        private String environment;
    }

    @Data
    public static class Llm {
        private boolean enabled;
        private String baseUrl;
        private String apiKey;
        private String model;
    }

    @Data
    public static class Security {
        private int maxPendingActions;
        private long pendingActionTimeoutMs;
    }

    @Data
    public static class Audit {
        private boolean enabled;
        private int pageSize;
    }
}
