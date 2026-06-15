package com.kylinops.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kylinops.auth.AbsoluteSessionExpiryFilter;
import com.kylinops.auth.AdminAuthenticationService;
import com.kylinops.auth.ApiRateLimiter;
import com.kylinops.auth.AuthProperties;
import com.kylinops.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * 安全配置 — 保护业务 API 边界。
 *
 * <h3>安全规则</h3>
 * <ul>
 *   <li>{@code /api/health}, {@code /api/health/live}, {@code /api/health/ready}, {@code /api/auth/login} → {@code permitAll()}</li>
 *   <li>{@code /api/**} → {@code authenticated()}</li>
 *   <li>其它请求 → {@code permitAll()}（静态资源等）</li>
 * </ul>
 *
 * <h3>认证入口点</h3>
 * 未认证请求返回 401 JSON 响应，格式统一为 {@link ApiResponse}。
 *
 * <h3>CSRF</h3>
 * 启用默认 CSRF 保护，{@code /api/auth/login} 豁免（登录入口）。
 *
 * <h3>Session（新增 P2-T2）</h3>
 * <ul>
 *   <li>Spring Session 空闲超时 = {@code kylinops.auth.idle-timeout}（默认 30m）</li>
 *   <li>{@link AbsoluteSessionExpiryFilter} 在 UsernamePasswordAuthenticationFilter 之后执行</li>
 *   <li>Cookie 序列化：prod Secure=true / SameSite=Strict；dev/test Secure=false</li>
 *   <li>/api/chat/send 由 {@link ApiRateLimiter} 拦截限流（30/分钟/会话）</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig implements WebMvcConfigurer {

    private final ObjectMapper objectMapper;
    private final AuthProperties authProperties;
    private final ObjectProvider<AdminAuthenticationService> authnServiceProvider;
    private final ObjectProvider<Clock> clockProvider;
    private final Environment environment;

    @Value("${kylinops.auth.chat-rate-limit:30}")
    private int chatRateLimit;

    @Value("${kylinops.auth.chat-rate-window:1m}")
    private Duration chatRateWindow;

    public SecurityConfig(ObjectMapper objectMapper,
                          AuthProperties authProperties,
                          ObjectProvider<AdminAuthenticationService> authnServiceProvider,
                          ObjectProvider<Clock> clockProvider,
                          Environment environment) {
        this.objectMapper = objectMapper;
        this.authProperties = authProperties;
        this.authnServiceProvider = authnServiceProvider;
        this.clockProvider = clockProvider;
        this.environment = environment;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/health", "/api/health/live", "/api/health/ready", "/api/auth/login").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
            )
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/api/auth/login")
            )
            .httpBasic(org.springframework.security.config.Customizer.withDefaults())
            .sessionManagement(session -> session
                .sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.IF_REQUIRED)
                .maximumSessions(1)
            );
        // 仅当 AdminAuthenticationService 与 Clock 都存在时挂载绝对超时过滤器（@WebMvcTest 切片下没有）
        AdminAuthenticationService authn = authnServiceProvider.getIfAvailable();
        Clock clk = clockProvider.getIfAvailable();
        if (authn != null && clk != null) {
            http.addFilterAfter(new AbsoluteSessionExpiryFilter(authn, objectMapper, clk),
                                UsernamePasswordAuthenticationFilter.class);
        }
        http
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) ->
                    ApiResponse.writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED,
                        "未认证，请先登录", objectMapper)
                )
                .accessDeniedHandler((request, response, accessDeniedException) ->
                    ApiResponse.writeJsonError(response, HttpServletResponse.SC_FORBIDDEN,
                        "权限不足", objectMapper)
                )
            );

        return http.build();
    }

    /**
     * Cookie 配置由 application*.yml 中的 {@code server.servlet.session.cookie.*} 驱动
     * （HttpOnly / Secure / SameSite 全部支持），避免引入 spring-session 依赖。
     * profile-driven 切换：prod 启用 Secure，dev/test 关闭（HTTP 本地可跑）。
     */

    @Bean
    public ApiRateLimiter chatRateLimiter() {
        Clock clk = clockProvider.getIfAvailable();
        if (clk == null) {
            // @WebMvcTest 切片下没有 Clock — 提供一个默认 UTC
            clk = java.time.Clock.systemUTC();
        }
        return new ApiRateLimiter(chatRateLimit, chatRateWindow, clk, objectMapper);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(chatRateLimiter())
                .addPathPatterns("/api/chat/send");
    }

    public AuthProperties authProperties() {
        return authProperties;
    }

    private boolean isProfileActive(String profile) {
        for (String p : environment.getActiveProfiles()) {
            if (p.equalsIgnoreCase(profile)) return true;
        }
        return false;
    }

    private void writeJsonResponse(HttpServletResponse response, ApiResponse<?> apiResponse) {
        try {
            objectMapper.writeValue(response.getOutputStream(), apiResponse);
        } catch (IOException e) {
            // 无法写入响应体时静默处理
        }
    }
}
