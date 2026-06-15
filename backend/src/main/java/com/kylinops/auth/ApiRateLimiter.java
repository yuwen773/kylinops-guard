package com.kylinops.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kylinops.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API 限流器（per-session 滑动窗口）。
 *
 * <h3>规则</h3>
 * 同一已认证 session 在 {@code window} 内最多 {@code limit} 次请求，默认 30/分钟。
 *
 * <h3>使用</h3>
 * 注册为 Spring MVC {@code HandlerInterceptor}：仅对 {@code /api/chat/send} 生效，
 * 跑在 SecurityContext 之后、Controller 之前。
 */
public class ApiRateLimiter implements HandlerInterceptor {

    private final int limit;
    private final Duration window;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, Deque<Instant>> buckets = new ConcurrentHashMap<>();

    public ApiRateLimiter(int limit, Duration window, Clock clock, ObjectMapper objectMapper) {
        this.limit = limit;
        this.window = window;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    public ApiRateLimiter(int limit, Duration window, Clock clock) {
        this(limit, window, clock, new ObjectMapper());
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String key = bucketKey(request);
        if (key == null) {
            return true; // 未认证或无 session — 留给 SecurityConfig 处理 401
        }
        Instant now = clock.instant();
        Deque<Instant> q = buckets.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            Instant cutoff = now.minus(window);
            while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) {
                q.pollFirst();
            }
            if (q.size() >= limit) {
                write429(response, "请求过于频繁，请稍后再试");
                return false;
            }
            q.offerLast(now);
        }
        return true;
    }

    private String bucketKey(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            return "sid:" + session.getId();
        }
        // 退化：以 principal 名（admin）作为 key
        String principal = authnPrincipal(request);
        return principal == null ? null : "u:" + principal;
    }

    private String authnPrincipal(HttpServletRequest request) {
        HttpSession s = request.getSession(false);
        if (s == null) return null;
        Object p = s.getAttribute(AdminAuthenticationService.SESSION_PRINCIPAL);
        return p instanceof String str ? str : null;
    }

    private void write429(HttpServletResponse response, String message) throws Exception {
        ApiResponse.writeJsonError(response, HttpStatus.TOO_MANY_REQUESTS.value(), message, objectMapper);
    }
}
