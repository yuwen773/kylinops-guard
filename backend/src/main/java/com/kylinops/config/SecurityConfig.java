package com.kylinops.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kylinops.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;
import java.util.UUID;

/**
 * 安全配置 — 保护业务 API 边界
 *
 * <h3>安全规则</h3>
 * <ul>
 *   <li>{@code /api/health}, {@code /api/health/live}, {@code /api/auth/login} → {@code permitAll()}</li>
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
 * <h3>Session</h3>
 * 使用默认 HttpSession（非无状态）。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/health", "/api/health/live", "/api/auth/login").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
            )
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/api/auth/login")
            )
            .httpBasic(Customizer.withDefaults())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setContentType("application/json;charset=UTF-8");
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    ApiResponse<Void> apiResponse = ApiResponse.error(401, "未认证，请先登录");
                    String traceId = UUID.randomUUID().toString().replaceAll("-", "").substring(0, 12);
                    apiResponse.traceId(traceId);
                    writeJsonResponse(response, apiResponse);
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setContentType("application/json;charset=UTF-8");
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    ApiResponse<Void> apiResponse = ApiResponse.error(403, "权限不足");
                    String traceId = UUID.randomUUID().toString().replaceAll("-", "").substring(0, 12);
                    apiResponse.traceId(traceId);
                    writeJsonResponse(response, apiResponse);
                })
            );

        return http.build();
    }

    private void writeJsonResponse(HttpServletResponse response, ApiResponse<?> apiResponse) {
        try {
            objectMapper.writeValue(response.getOutputStream(), apiResponse);
        } catch (IOException e) {
            // 无法写入响应体时静默处理
        }
    }
}
