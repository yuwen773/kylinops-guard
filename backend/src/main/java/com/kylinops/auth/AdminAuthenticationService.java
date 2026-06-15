package com.kylinops.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 单管理员认证服务。
 *
 * <h3>职责</h3>
 * <ol>
 *   <li>BCrypt 校验（{@link BCryptPasswordEncoder#matches})</li>
 *   <li>登录成功后写入 HttpSession 属性：principal / loginAt</li>
 *   <li>调用 {@code request.changeSessionId()} 轮换 session id（防 session fixation）</li>
 *   <li>返回 Spring CSRF token（从 request attribute "_csrf"）</li>
 * </ol>
 */
@Service
public class AdminAuthenticationService {

    public static final String SESSION_PRINCIPAL = "kylinops.principal";
    public static final String SESSION_LOGIN_AT = "kylinops.loginAt";

    private final AuthProperties props;
    private final BCryptPasswordEncoder passwordEncoder;
    private final Clock clock;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public AdminAuthenticationService(AuthProperties props, Clock clock) {
        this.props = props;
        this.passwordEncoder = new BCryptPasswordEncoder();
        this.clock = clock;
    }

    /**
     * 校验凭据。
     *
     * @return true 当且仅当 username 匹配且 password BCrypt 校验通过
     */
    public boolean verify(String username, String password) {
        if (username == null || password == null) return false;
        if (!props.username().equals(username)) {
            // 对错误用户名也跑一次 BCrypt，避免时序攻击泄露用户名
            passwordEncoder.matches(password, "$2a$10$invalidsaltinvalidsaltinvalidsaltinvalidsalt0000");
            return false;
        }
        String hash = props.passwordHash();
        if (hash == null || hash.isBlank()) {
            return false;
        }
        return passwordEncoder.matches(password, hash);
    }

    /**
     * 登录成功：轮换 session id、写入 principal 与 loginAt、建立 SecurityContext。
     * <p>
     * 必须建立 SecurityContext，否则 Spring Security 的 {@code authenticated()} 规则
     * 视为匿名，后续 /api/** 请求会 401。
     * </p>
     */
    public void onLoginSuccess(HttpServletRequest request, HttpServletResponse response, String username) {
        // 防 session fixation：先获取 session，再轮换 id
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        HttpSession newSession = request.getSession(false);
        newSession.setAttribute(SESSION_PRINCIPAL, username);
        newSession.setAttribute(SESSION_LOGIN_AT, Instant.now(clock));

        // 建立 SecurityContext — Spring Security 用它判定 authenticated()
        Authentication auth = new UsernamePasswordAuthenticationToken(
                username, "N/A", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        // 持久化到 session，使后续请求复用
        securityContextRepository.saveContext(context, request, response);
    }

    /**
     * 从已认证 session 读取 principal。
     */
    public String currentPrincipal(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        Object p = session.getAttribute(SESSION_PRINCIPAL);
        return p instanceof String s ? s : null;
    }

    /**
     * 从已认证 session 读取 loginAt。
     */
    public Instant currentLoginAt(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        Object v = session.getAttribute(SESSION_LOGIN_AT);
        return v instanceof Instant i ? i : null;
    }

    public AuthProperties properties() {
        return props;
    }

    public Clock clock() {
        return clock;
    }

    public Duration absoluteTimeout() {
        return props.absoluteTimeout();
    }
}
