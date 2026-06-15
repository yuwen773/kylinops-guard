package com.kylinops.config;

import com.kylinops.auth.AuthProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Auth 配套 Bean — Clock 与 AuthProperties 启用。
 * <p>
 * KylinOpsConfig 是被 SafeExecutor 等组件直接 @Autowired 引用的 POJO，扩展它会引入
 * 重复 @Configuration 注解冲突；故单建本类做配套 Bean 注册，不修改原文件。
 * </p>
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthBeansConfig {

    /**
     * 系统 Clock — 默认 UTC，测试可注入 Clock.fixed 验证时间相关逻辑。
     */
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
