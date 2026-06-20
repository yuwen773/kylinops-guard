package com.kylinops.inspection;

import com.kylinops.inspection.model.InspectionScheduleType;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 巡检计划下一次触发时刻计算器。
 *
 * <p>严格 afterExclusive 语义:返回时刻必须严格大于入参 afterExclusive。
 *
 * <p>DST 处理:
 * <ul>
 *   <li>Spring forward gap(LocalTime 不存在):JDK ZonedDateTime 自动顺延到第一个有效时刻。</li>
 *   <li>Fall back overlap(LocalTime 出现两次):JDK 默认取较早偏移,重叠日只触发一次,下一次为次日同 LocalTime。</li>
 * </ul>
 *
 * <p>MONTHLY dayOfMonth 限制在 1..28,避免 Feb 闰年歧义;非整数或越界直接 IllegalArgumentException。
 */
public class InspectionScheduleCalculator {

    /**
     * 计算下一次触发时刻。
     *
     * @param type           调度类型
     * @param config         调度配置(localTime 必填,WEEKLY 必填 dayOfWeek,MONTHLY 必填 dayOfMonth 1..28)
     * @param zone           计划绑定的时区(决定 DST 与本地时间到 Instant 的映射)
     * @param afterExclusive 严格大于此 instant 才算下一次;若 afterExclusive 本身落在触发时刻上,顺延一轮
     * @return 下一次触发的 Instant
     * @throws IllegalArgumentException 配置与类型不匹配或 dayOfMonth 越界
     */
    public Instant nextRun(
            InspectionScheduleType type,
            InspectionScheduleConfig config,
            ZoneId zone,
            Instant afterExclusive) {

        validate(type, config);

        ZonedDateTime after = afterExclusive.atZone(zone);
        ZonedDateTime next = switch (type) {
            case DAILY -> nextDaily(config.localTime(), zone, after);
            case WEEKLY -> nextWeekly(config.localTime(), config.dayOfWeek(), zone, after);
            case MONTHLY -> nextMonthly(config.localTime(), config.dayOfMonth(), zone, after);
        };
        return next.toInstant();
    }

    private static void validate(InspectionScheduleType type, InspectionScheduleConfig cfg) {
        switch (type) {
            case DAILY -> {
                // no extra requirements
            }
            case WEEKLY -> {
                if (cfg.dayOfWeek() == null) {
                    throw new IllegalArgumentException(
                            "WEEKLY schedule requires dayOfWeek in config");
                }
            }
            case MONTHLY -> {
                Integer dom = cfg.dayOfMonth();
                if (dom == null) {
                    throw new IllegalArgumentException(
                            "MONTHLY schedule requires dayOfMonth in config");
                }
                if (dom < 1 || dom > 28) {
                    throw new IllegalArgumentException(
                            "MONTHLY dayOfMonth must be in 1..28 to avoid Feb edge cases, got: "
                                    + dom);
                }
            }
        }
    }

    private static ZonedDateTime nextDaily(LocalTime time, ZoneId zone, ZonedDateTime after) {
        ZonedDateTime candidate = withTime(after, time);
        if (!candidate.isAfter(after)) {
            LocalDate tomorrow = candidate.toLocalDate().plusDays(1);
            // 用 LocalDate + LocalTime + ZoneId 重构:让 JDK 处理 DST gap / overlap 的常规语义
            candidate = ZonedDateTime.of(tomorrow, time, zone);
        }
        return candidate;
    }

    private static ZonedDateTime nextWeekly(LocalTime time, DayOfWeek dow, ZoneId zone,
                                            ZonedDateTime after) {
        ZonedDateTime candidate = withTime(after, time);
        int diff = (dow.getValue() - candidate.getDayOfWeek().getValue() + 7) % 7;
        LocalDate targetDate = candidate.toLocalDate().plusDays(diff);
        candidate = ZonedDateTime.of(targetDate, time, zone);
        if (!candidate.isAfter(after)) {
            // 同 dayOfWeek 但 LocalTime 已等于 afterExclusive(strictly after 已排除,此处兜底"等于")
            candidate = ZonedDateTime.of(targetDate.plusDays(7), time, zone);
        }
        return candidate;
    }

    private static ZonedDateTime nextMonthly(LocalTime time, int dom, ZoneId zone,
                                             ZonedDateTime after) {
        LocalDate targetDate = after.toLocalDate().withDayOfMonth(dom);
        ZonedDateTime candidate = ZonedDateTime.of(targetDate, time, zone);
        if (!candidate.isAfter(after)) {
            YearMonth nextMonth = YearMonth.from(after.toLocalDate()).plusMonths(1);
            candidate = ZonedDateTime.of(nextMonth.atDay(dom), time, zone);
        }
        return candidate;
    }

    /**
     * 把 ZonedDateTime 替换为指定 LocalTime,清零秒/纳秒;JDK 在 DST gap 上自动向前顺延,overlap 取较早偏移。
     */
    private static ZonedDateTime withTime(ZonedDateTime zdt, LocalTime time) {
        return zdt.withHour(time.getHour())
                .withMinute(time.getMinute())
                .withSecond(time.getSecond())
                .withNano(time.getNano());
    }
}