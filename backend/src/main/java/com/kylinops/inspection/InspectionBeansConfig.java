package com.kylinops.inspection;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 巡检模块配套 Bean 配置。
 *
 * <p>注册 {@link InspectionProperties} 配置绑定 + {@link InspectionScheduleCalculator}
 * 周期计算器(Task 1 纯 POJO,本 Task 6 提升为 Bean 供 Service 注入)。</p>
 */
@Configuration
@EnableConfigurationProperties(InspectionProperties.class)
public class InspectionBeansConfig {

    @Bean
    public InspectionScheduleCalculator inspectionScheduleCalculator() {
        return new InspectionScheduleCalculator();
    }
}