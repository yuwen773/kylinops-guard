package com.kylinops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置
 * <p>
 * 配置 CORS（前端 Vite dev server 跨域访问）、
 * 消息转换器、拦截器等全局 Web 设置。
 * </p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * CORS 跨域配置
     * <p>
     * 允许前端开发服务器（Vite 默认 5173）和打包后的静态资源跨域访问。
     * 生产环境应收紧 origin 白名单。
     * </p>
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOriginPattern("*");
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return new CorsFilter(source);
    }
}
