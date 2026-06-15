package com.kylinops.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kylinops.common.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 绝对会话过期过滤器 — 在 session 上的 loginAt 超过 absolute-timeout 时强制失效。
 *
 * <h3>执行顺序</h3>
 * 应注册在 SecurityContextHolderFilter 之后（authenticated 链中），未认证时直接跳过。
 *
 * <h3>行为</h3>
 * <ul>
 *   <li>未认证 / 缺 loginAt：跳过</li>
 *   <li>登录时间 < now - absoluteTimeout：invalidate session + 401 JSON</li>
 *   <li>否则：放行</li>
 * </ul>
 */
public class AbsoluteSessionExpiryFilter extends OncePerRequestFilter {

    private final AdminAuthenticationService authnService;
    private final Duration absoluteTimeout;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AbsoluteSessionExpiryFilter(AdminAuthenticationService authnService,
                                       ObjectMapper objectMapper,
                                       Clock clock) {
        this.authnService = authnService;
        this.absoluteTimeout = authnService.absoluteTimeout();
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 仅对已认证请求生效
        if (SecurityContextHolder.getContext().getAuthentication() == null
                || !SecurityContextHolder.getContext().getAuthentication().isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }
        Instant loginAt = authnService.currentLoginAt(request);
        if (loginAt == null) {
            chain.doFilter(request, response);
            return;
        }
        Instant now = clock.instant();
        if (loginAt.plus(absoluteTimeout).isBefore(now)) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
            writeUnauthorized(response, "会话已过期（绝对超时），请重新登录");
            return;
        }
        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        ApiResponse.writeJsonError(response, HttpStatus.UNAUTHORIZED.value(), message, objectMapper);
    }
}
