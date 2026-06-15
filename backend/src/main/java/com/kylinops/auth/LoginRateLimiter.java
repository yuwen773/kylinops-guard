package com.kylinops.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 登录限流器（per-IP + per-IP+username 双维度）。
 *
 * <h3>规则</h3>
 * <ul>
 *   <li>IP 维度：10 次/分钟（滑动窗口，超出则 429 候选）</li>
 *   <li>username 维度：连续失败 5 次触发 15 分钟锁定（同一 IP+username 计数）</li>
 *   <li>锁定时返回 423 Locked</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * 使用 {@link ConcurrentHashMap} + 内部 {@code synchronized} 块保护 Deque。
 * 测试可注入 {@link Clock#fixed} 验证时间窗口。
 */
@Component
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    private final Clock clock;
    private final int maxIpPerMinute;
    private final int maxFailures;
    private final Duration lockDuration;
    private final Duration failureWindow;

    private final ConcurrentHashMap<String, Deque<Instant>> ipAttempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<Instant>> usernameFailures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> usernameLockedUntil = new ConcurrentHashMap<>();

    public LoginRateLimiter(Clock clock, AuthProperties props) {
        this.clock = clock;
        this.maxIpPerMinute = 10;
        this.maxFailures = props.maxFailures();
        this.lockDuration = props.lockDuration();
        // 失败窗口默认 = lockDuration，最小 1 分钟（保证连续失败能正确累加）
        Duration failureWindow = props.lockDuration();
        if (failureWindow.toMinutes() < 1) {
            this.failureWindow = Duration.ofMinutes(1);
        } else {
            this.failureWindow = failureWindow;
        }
    }

    /**
     * 限流判定。
     *
     * @return {@link Decision} 描述下一步：ALLOW / RATE_LIMITED / LOCKED
     */
    public Decision check(String ip, String username) {
        Instant now = clock.instant();
        // 1) 用户名锁定检查
        Instant lockedUntil = usernameLockedUntil.get(key(ip, username));
        if (lockedUntil != null && now.isBefore(lockedUntil)) {
            return Decision.LOCKED;
        } else if (lockedUntil != null) {
            // 锁定到期，清理
            usernameLockedUntil.remove(key(ip, username));
            usernameFailures.remove(key(ip, username));
        }
        // 2) IP 滑动窗口
        Deque<Instant> ipq = ipAttempts.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (ipq) {
            Instant cutoff = now.minus(Duration.ofMinutes(1));
            while (!ipq.isEmpty() && ipq.peekFirst().isBefore(cutoff)) {
                ipq.pollFirst();
            }
            if (ipq.size() >= maxIpPerMinute) {
                return Decision.RATE_LIMITED;
            }
        }
        return Decision.ALLOW;
    }

    /**
     * 记录一次成功登录 — 清除该 IP+username 的失败计数与锁定。
     */
    public void recordSuccess(String ip, String username) {
        usernameFailures.remove(key(ip, username));
        usernameLockedUntil.remove(key(ip, username));
    }

    /**
     * 记录一次失败登录 — 累加 IP 计数与 username 失败计数，必要时触发锁定。
     */
    public void recordFailure(String ip, String username) {
        Instant now = clock.instant();
        // IP 滑动窗口累加
        Deque<Instant> ipq = ipAttempts.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (ipq) {
            Instant cutoff = now.minus(Duration.ofMinutes(1));
            while (!ipq.isEmpty() && ipq.peekFirst().isBefore(cutoff)) {
                ipq.pollFirst();
            }
            ipq.offerLast(now);
        }
        // username 失败窗口（独立于 lockDuration，避免短 lockDuration 抹掉计数）
        Deque<Instant> uq = usernameFailures.computeIfAbsent(key(ip, username), k -> new ArrayDeque<>());
        synchronized (uq) {
            Instant cutoff = now.minus(failureWindow);
            while (!uq.isEmpty() && uq.peekFirst().isBefore(cutoff)) {
                uq.pollFirst();
            }
            uq.offerLast(now);
            if (uq.size() >= maxFailures) {
                usernameLockedUntil.put(key(ip, username), now.plus(lockDuration));
            }
        }
        log.debug("recordFailure ip={} user={} uq.size={} maxFailures={} lockUntil={}",
                ip, username, uq.size(), maxFailures, usernameLockedUntil.get(key(ip, username)));
    }

    private static String key(String ip, String username) {
        return ip + "|" + (username == null ? "" : username);
    }

    /**
     * 清空所有计数与锁定状态（仅供测试 @BeforeEach 使用）。
     */
    public void reset() {
        ipAttempts.clear();
        usernameFailures.clear();
        usernameLockedUntil.clear();
    }

    public enum Decision {
        ALLOW,
        RATE_LIMITED,
        LOCKED
    }
}
