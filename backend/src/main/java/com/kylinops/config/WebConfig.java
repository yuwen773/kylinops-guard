package com.kylinops.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web 配置
 * <p>
 * 配置 CORS（前端 Vite dev server 跨域访问）、
 * 消息转换器、拦截器等全局 Web 设置。
 * </p>
 *
 * <h3>CORS Profile 策略</h3>
 * <ul>
 *   <li>{@code dev} — 允许 Vite dev server (localhost:5173) + 127.0.0.1:5173</li>
 *   <li>{@code test} — 允许 localhost:5173 + 127.0.0.1:5173</li>
 *   <li>{@code prod} — 仅允许同源（不设置 Access-Control-Allow-Origin）</li>
 * </ul>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${kylinops.app.environment:dev}")
    private String environment;

    /**
     * CORS 跨域配置，按 profile 控制允许来源。
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        if ("prod".equals(environment)) {
            // 生产环境：仅同源，不设置跨域
            config.setAllowedOriginPatterns(List.of());
            config.setAllowCredentials(false);
        } else {
            // 开发/测试环境：允许 Vite dev server
            config.setAllowedOriginPatterns(List.of(
                    "http://localhost:5173",
                    "http://127.0.0.1:5173"
            ));
            config.setAllowCredentials(true);
        }

        config.setAllowedHeaders(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return new CorsFilter(source);
    }
}
