package com.kylinops.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * SPA 前端路由回退过滤器（仅 standalone profile 激活）。
 *
 * <p>Vue Router 使用 History 模式时，刷新 /dashboard、/tools 等路径
 * 会直接请求后端，而这些路径没有对应的静态文件。此 Filter 将非 API、
 * 无扩展名的请求转发到 {@code /index.html}，让 Vue Router 接管路由。</p>
 *
 * <p>仅在 {@code standalone} profile 下激活 —— dev/profile 模式下
 * Vite dev server 自带路由回退，无需此 Filter。</p>
 */
@Profile("standalone")
@Component
@Order(1_000_000) // 置于末尾，确保业务 Filter 已处理完毕
public class SpaFallbackFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // API 调用、根路径 → 正常处理
        if (path.startsWith("/api") || "/".equals(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 有扩展名的静态资源（.js, .css, .png, .ico, .json 等）→ 正常处理
        if (path.contains(".")) {
            filterChain.doFilter(request, response);
            return;
        }

        // SPA 前端路由（如 /dashboard, /tools, /security, /audit, /reports）
        // → 透传 index.html，由 Vue Router 客户端路由
        request.getRequestDispatcher("/index.html").forward(request, response);
    }
}
