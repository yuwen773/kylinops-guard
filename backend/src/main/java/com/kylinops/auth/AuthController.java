package com.kylinops.auth;

import com.kylinops.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * 单管理员认证 Controller。
 *
 * <h3>端点</h3>
 * <ul>
 *   <li>POST /api/auth/login — 用户名/密码登录，200 + 摘要；401 错误凭据；429 限流；423 锁定</li>
 *   <li>GET /api/auth/session — 已认证返回 principal / csrfToken / loginAt</li>
 *   <li>POST /api/auth/logout — 204 + invalidate</li>
 * </ul>
 *
 * <h3>CSRF</h3>
 * 登录入口在 SecurityConfig 中已配置 CSRF 豁免；其它端点 CSRF 由 Spring Security 默认 CsrfFilter 处理。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final String GENERIC_AUTH_FAIL_MSG = "用户名或密码错误";

    private final AdminAuthenticationService authnService;
    private final LoginRateLimiter rateLimiter;

    public AuthController(AdminAuthenticationService authnService, LoginRateLimiter rateLimiter) {
        this.authnService = authnService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<?>> login(@Valid @RequestBody LoginRequest body,
                                                HttpServletRequest request,
                                                HttpServletResponse response) {
        String ip = clientIp(request);
        String username = body.username();

        // 1. 先校验凭据
        boolean ok = authnService.verify(username, body.password());

        if (!ok) {
            // 2. 失败计数 + 1
            rateLimiter.recordFailure(ip, username);

            // 3. 失败计数达到 max-failures 时立即返回 423（在 rate-limit 检查之前）
            if (rateLimiter.isLocked(ip, username)) {
                log.warn("登录触发锁定: ip={}, username={}", ip, username);
                return ResponseEntity.status(HttpStatus.LOCKED)
                        .body(ApiResponse.error(423, "账户已锁定，请稍后再试"));
            }

            log.warn("登录失败: ip={}, username={}", ip, username);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, GENERIC_AUTH_FAIL_MSG));
        }

        // 4. 通过 rate-limit 检查（成功路径也需检查，防止被锁定但密码碰巧正确）
        LoginRateLimiter.Decision decision = rateLimiter.check(ip, username);
        if (decision == LoginRateLimiter.Decision.LOCKED) {
            log.warn("登录被锁定: ip={}, username={}", ip, username);
            return ResponseEntity.status(HttpStatus.LOCKED)
                    .body(ApiResponse.error(423, "账户已锁定，请稍后再试"));
        }
        if (decision == LoginRateLimiter.Decision.RATE_LIMITED) {
            log.warn("登录被限流: ip={}, username={}", ip, username);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error(429, "请求过于频繁，请稍后再试"));
        }

        // 5. 通过后返回 200 + sessionId
        rateLimiter.recordSuccess(ip, username);
        authnService.onLoginSuccess(request, response, username);

        // 读取 CSRF token（CsrfFilter 已经在登录请求路径填充）
        String csrfToken = currentCsrfToken(request);

        Instant loginAt = authnService.currentLoginAt(request);
        Instant expiresAt = loginAt.plus(authnService.absoluteTimeout());
        long idleSec = authnService.properties().idleTimeout().toSeconds();

        AuthSessionResponse data = new AuthSessionResponse(username, csrfToken, loginAt, expiresAt, idleSec);
        log.info("登录成功: ip={}, username={}", ip, username);
        return ResponseEntity.ok(ApiResponse.success(data, "登录成功"));
    }

    @GetMapping("/session")
    public ResponseEntity<ApiResponse<AuthSessionResponse>> session(HttpServletRequest request) {
        String principal = authnService.currentPrincipal(request);
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "未认证"));
        }
        Instant loginAt = authnService.currentLoginAt(request);
        Instant expiresAt = loginAt != null ? loginAt.plus(authnService.absoluteTimeout()) : null;
        String csrfToken = currentCsrfToken(request);
        long idleSec = authnService.properties().idleTimeout().toSeconds();
        AuthSessionResponse data = new AuthSessionResponse(principal, csrfToken, loginAt, expiresAt, idleSec);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            String principal = authnService.currentPrincipal(request);
            log.info("登出: username={}", principal);
            session.invalidate();
        }
        return ResponseEntity.noContent().build();
    }

    // ==================== 辅助 ====================

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    private static String currentCsrfToken(HttpServletRequest request) {
        Object attr = request.getAttribute(CsrfToken.class.getName());
        if (attr instanceof CsrfToken t) {
            return t.getToken();
        }
        // 兜底：deferred token 走 _csrf
        Object deferred = request.getAttribute("_csrf");
        if (deferred instanceof CsrfToken t) {
            return t.getToken();
        }
        return UUID.randomUUID().toString();
    }
}
