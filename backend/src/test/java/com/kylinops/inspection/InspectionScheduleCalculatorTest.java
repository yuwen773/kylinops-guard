package com.kylinops.inspection;

import com.kylinops.inspection.model.InspectionScheduleType;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InspectionScheduleCalculator 单元测试:基于 Clock.fixed 风格的固定 Instant 断言
 * DAILY/WEEKLY/MONTHLY 在 Asia/Shanghai 与 America/New_York 下的语义,含 DST 跳变与重叠。
 * 纯 POJO 测试,不启动 Spring Context。
 */
class InspectionScheduleCalculatorTest {

    private final InspectionScheduleCalculator calculator = new InspectionScheduleCalculator();

    @Test
    void dailyUsesPlanTimezone() {
        // 04:00 Shanghai 之后的下一次 08:00 Shanghai = 同日 08:00 = 2026-06-19T00:00:00Z
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        Instant after = Instant.parse("2026-06-18T20:00:00Z"); // 04:00 Shanghai 2026-06-19
        InspectionScheduleConfig cfg = new InspectionScheduleConfig(LocalTime.of(8, 0), null, null);

        Instant next = calculator.nextRun(InspectionScheduleType.DAILY, cfg, shanghai, after);

        assertEquals(Instant.parse("2026-06-19T00:00:00Z"), next);
    }

    @Test
    void weeklyUsesJavaDayOfWeek() {
        // 2026-06-19 是 Friday;配置 Monday 09:00 UTC,期望 2026-06-22T09:00:00Z
        ZoneId utc = ZoneId.of("UTC");
        Instant after = Instant.parse("2026-06-19T00:00:00Z"); // Friday 00:00 UTC
        InspectionScheduleConfig cfg = new InspectionScheduleConfig(
                LocalTime.of(9, 0), DayOfWeek.MONDAY, null);

        Instant next = calculator.nextRun(InspectionScheduleType.WEEKLY, cfg, utc, after);

        assertEquals(Instant.parse("2026-06-22T09:00:00Z"), next);
    }

    @Test
    void monthlyRejectsDay29() {
        // MONTHLY + dayOfMonth=29 在 nextRun 阶段直接拒绝,Feb 非闰年下不可靠
        InspectionScheduleConfig cfg = new InspectionScheduleConfig(
                LocalTime.of(10, 0), null, 29);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> calculator.nextRun(InspectionScheduleType.MONTHLY, cfg,
                        ZoneId.of("UTC"), Instant.parse("2026-06-19T10:00:00Z")));

        // 异常信息应明示 dayOfMonth 限制范围,便于排错
        String msg = ex.getMessage();
        assertTrue(msg != null && !msg.isBlank(),
                "exception message should not be blank");
        String lower = msg.toLowerCase();
        assertTrue(lower.contains("dayofmonth") || lower.contains("28"),
                "expected message to mention dayOfMonth or 28-day limit, got: " + msg);
    }

    @Test
    void springForwardMovesToFirstValidInstant() {
        // 2026-03-08 02:00 EST → 03:00 EDT;02:30 不存在,期望跳到第一个有效时刻 03:30 EDT
        ZoneId ny = ZoneId.of("America/New_York");
        Instant after = Instant.parse("2026-03-07T12:00:00Z"); // 07:00 EST 2026-03-07
        InspectionScheduleConfig cfg = new InspectionScheduleConfig(
                LocalTime.of(2, 30), null, null);

        Instant next = calculator.nextRun(InspectionScheduleType.DAILY, cfg, ny, after);

        // 03:30 EDT = 07:30 UTC
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), next);
    }

    @Test
    void fallBackUsesEarlierOffsetOnlyOnce() {
        // 2026-11-01 02:00 EDT → 01:00 EST;01:30 出现两次,Java 取较早偏移(EDT,UTC-4)
        // 关键不变量:重叠日 01:30 只产生一次触发,不重复 EST 那次
        ZoneId ny = ZoneId.of("America/New_York");
        Instant after = Instant.parse("2026-10-31T12:00:00Z"); // 08:00 EDT 2026-10-31
        InspectionScheduleConfig cfg = new InspectionScheduleConfig(
                LocalTime.of(1, 30), null, null);

        Instant first = calculator.nextRun(InspectionScheduleType.DAILY, cfg, ny, after);
        // 第一次:2026-11-01 01:30 EDT = 05:30 UTC
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), first);

        // 从 first 再求下一次:不应再回到当天 01:30 EST,而应跳到次日 01:30 EST
        Instant second = calculator.nextRun(InspectionScheduleType.DAILY, cfg, ny, first);
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), second);
    }

    @Test
    void missedRunCalculatesNextFutureOccurrence() {
        // 当前 10:00 UTC,08:00 daily 已过;下一次应为明天 08:00 UTC
        ZoneId utc = ZoneId.of("UTC");
        Instant now = Instant.parse("2026-06-19T10:00:00Z");
        InspectionScheduleConfig cfg = new InspectionScheduleConfig(
                LocalTime.of(8, 0), null, null);

        Instant next = calculator.nextRun(InspectionScheduleType.DAILY, cfg, utc, now);

        assertEquals(Instant.parse("2026-06-20T08:00:00Z"), next);
    }
}