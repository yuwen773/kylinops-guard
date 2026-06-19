package com.kylinops.inspection;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 巡检模块配套 Bean 配置。
 *
 * <p>仅注册 {@link InspectionProperties} 配置绑定,实际执行 / 调度 / 持久化
 * Bean 在后续 Task 中按需补齐。
 */
@Configuration
@EnableConfigurationProperties(InspectionProperties.class)
public class InspectionBeansConfig {
}