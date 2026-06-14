package com.kylinops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * 测试安全配置 — 提供内存用户用于集成测试认证。
 * <p>
 * 创建用户 {@code admin / test} 并授予 {@code ROLE_ADMIN} 角色，
 * 供 {@code @SpringBootTest} 或 {@code @WebMvcTest} 测试通过
 * {@code @Import(TestSecurityConfig.class)} 引入使用。
 * </p>
 */
@Configuration
public class TestSecurityConfig {

    @Bean
    public UserDetailsService testUserDetailsService() {
        var admin = User.withUsername("admin")
                .password("{noop}test")
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(admin);
    }
}
