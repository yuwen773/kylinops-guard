package com.kylinops.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LoginRateLimiter 单元测试 — 不依赖 Spring，直接验证锁定时间窗口的正确性。
 *
 * <h3>覆盖场景</h3>
 * <ul>
 *   <li>{@link #hammingDuringLockDoesNotExtendLock} — 锁定期内继续失败，锁定时长不被延长。
 *        这是 CI 上 {@code SessionExpiryAndRateLimitTest.lockoutExpiresAfterLockDuration}
 *        不稳定（pollUntil 5s 内条件未满足）的根因。</li>
 *   <li>{@link #lockExpiresAfterLockDuration} — 锁定到期后 isLocked 返回 false。</li>
 *   <li>{@link #hammingAfterLockExpiryResetsCounters} — 锁定到期后再失败 4 次
 *        不会立刻又触发锁定（5 次阈值）。</li>
 *   <li>{@link #successDuringLockClearsState} — 锁定期间成功登录会清空状态。</li>
 * </ul>
 */
class LoginRateLimiterTest {

    /** 构造测试用 rate limiter，参数与 SessionExpiryAndRateLimitTest 一致。 */
    private static LoginRateLimiter newLimiter(Clock clock) {
        AuthProperties props = new AuthProperties(
                "admin", "$2a$10$invalidsaltinvalidsaltinvalidsaltinvalidsalt0000",
                Duration.ofSeconds(10), Duration.ofSeconds(2),
                5, Duration.ofMillis(200));
        return new LoginRateLimiter(clock, props);
    }

    @Test
    @DisplayName("锁定期内继续失败：lockedUntil 不被延长（CI 回归 — pollUntil 根因）")
    void hammingDuringLockDoesNotExtendLock() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LoginRateLimiter rl = newLimiter(clock);

        // 4 次失败
        for (int i = 0; i < 4; i++) {
            rl.recordFailure("1.2.3.4", "admin");
            clock.advance(Duration.ofMillis(10));
        }
        // 第 5 次失败触发锁定（t5 = 40ms）
        rl.recordFailure("1.2.3.4", "admin");
        assertThat(rl.isLocked("1.2.3.4", "admin"))
                .as("5th failure triggers lock")
                .isTrue();

        // 在锁定期内（t5+50ms）继续失败 — 锁定应不被延长
        clock.advance(Duration.ofMillis(50));   // now = 90ms (t5+50)
        rl.recordFailure("1.2.3.4", "admin");
        // 跨过 200ms 锁定窗口后再 hammer 一次
        clock.advance(Duration.ofMillis(200));  // now = 290ms (t5+250, 已过锁定到期 t5+200)
        rl.recordFailure("1.2.3.4", "admin");
        // 由于锁定早已到期（t5+200 = 240ms），isLocked 应清理状态并返回 false
        assertThat(rl.isLocked("1.2.3.4", "admin"))
                .as("lock expires at t5 + 200ms regardless of hammering during lock window")
                .isFalse();
    }

    @Test
    @DisplayName("锁定到期 → isLocked 返回 false")
    void lockExpiresAfterLockDuration() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LoginRateLimiter rl = newLimiter(clock);

        for (int i = 0; i < 5; i++) {
            rl.recordFailure("1.2.3.4", "admin");
        }
        assertThat(rl.isLocked("1.2.3.4", "admin")).isTrue();

        clock.advance(Duration.ofMillis(199));
        assertThat(rl.isLocked("1.2.3.4", "admin"))
                .as("1ms before expiry still locked")
                .isTrue();

        clock.advance(Duration.ofMillis(2));
        assertThat(rl.isLocked("1.2.3.4", "admin"))
                .as("after expiry unlocked")
                .isFalse();
    }

    @Test
    @DisplayName("锁定到期后再失败 4 次不会立刻触发锁定")
    void hammingAfterLockExpiryResetsCounters() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LoginRateLimiter rl = newLimiter(clock);

        for (int i = 0; i < 5; i++) {
            rl.recordFailure("1.2.3.4", "admin");
        }
        // 锁定到期
        clock.advance(Duration.ofMillis(250));

        // 4 次失败（应该解锁后才开始计数）
        for (int i = 0; i < 4; i++) {
            rl.recordFailure("1.2.3.4", "admin");
            clock.advance(Duration.ofMillis(10));
        }
        assertThat(rl.isLocked("1.2.3.4", "admin"))
                .as("4 fresh failures do not retrigger lock")
                .isFalse();

        // 第 5 次才再次锁定
        rl.recordFailure("1.2.3.4", "admin");
        assertThat(rl.isLocked("1.2.3.4", "admin"))
                .as("5th fresh failure re-locks")
                .isTrue();
    }

    @Test
    @DisplayName("锁定期间成功登录 → 清空状态")
    void successDuringLockClearsState() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LoginRateLimiter rl = newLimiter(clock);

        for (int i = 0; i < 5; i++) {
            rl.recordFailure("1.2.3.4", "admin");
        }
        assertThat(rl.isLocked("1.2.3.4", "admin")).isTrue();

        rl.recordSuccess("1.2.3.4", "admin");
        assertThat(rl.isLocked("1.2.3.4", "admin"))
                .as("success clears lock state immediately")
                .isFalse();
    }

    /** 可变 Clock — 单元测试用 Mutex 推进时间，避免依赖 Thread.sleep。 */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant initial) {
            this.now = initial;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
