package com.kylinops.inspection;

import com.kylinops.inspection.model.InspectionScheduleType;
import com.kylinops.inspection.model.InspectionTemplateType;
import com.kylinops.os.BaseOSValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 巡检计划参数/调度/阈值校验器(P1-02 Task 3)。
 *
 * <p>所有校验失败抛 {@link InspectionValidationException},消息以 {@code [fieldName]} 前缀,
 * 便于 API 层透传字段名给前端表单做红框提示。</p>
 *
 * <p>纯 POJO:不依赖 Spring Context,通过构造函数注入允许服务清单
 * (从 {@link InspectionProperties} 适配)。</p>
 */
@Component
public class InspectionPlanValidator {

    /** 巡检专用服务 allowlist(从配置 {@code kylinops.inspection.allowed-services} 注入)。 */
    private final List<String> allowedServices;

    @Autowired
    public InspectionPlanValidator(InspectionProperties properties) {
        this(properties.getAllowedServices());
    }

    /** 测试/装配友好的便利构造器,允许直接传入 Map(从 YAML 配置读取后转交)。 */
    public InspectionPlanValidator(Map<String, Object> rawProperties) {
        this(extractAllowedServices(rawProperties));
    }

    public InspectionPlanValidator(List<String> allowedServices) {
        this.allowedServices = allowedServices == null ? List.of() : List.copyOf(allowedServices);
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractAllowedServices(Map<String, Object> raw) {
        if (raw == null) return List.of();
        Object list = raw.get("kylinops.inspection.allowed-services");
        if (list instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    /**
     * 入口校验。按模板类型分发到 {@link #validateHealth} / {@link #validateDisk}
     * / {@link #validateService} 并校验调度配置与时区。
     *
     * @param templateType    模板类型
     * @param params          模板参数
     * @param thresholds      阈值
     * @param scheduleType    调度类型
     * @param scheduleConfig  调度配置
     * @param timezone        IANA 时区 ID
     */
    public void validate(InspectionTemplateType templateType,
                         Map<String, Object> params,
                         Map<String, Object> thresholds,
                         InspectionScheduleType scheduleType,
                         InspectionScheduleConfig scheduleConfig,
                         String timezone) {
        if (templateType == null) {
            throw new InspectionValidationException("[templateType] 不能为空");
        }
        if (params == null) {
            throw new InspectionValidationException("[params] 不能为空");
        }
        if (scheduleConfig == null) {
            throw new InspectionValidationException("[scheduleConfig] 不能为空");
        }
        validateTimezone(timezone);
        validateSchedule(scheduleType, scheduleConfig);

        switch (templateType) {
            case HEALTH -> validateHealth(params, thresholds);
            case DISK -> validateDisk(params, thresholds);
            case SERVICE -> validateService(params);
        }
    }

    // ===== Per-template =====

    private void validateHealth(Map<String, Object> params, Map<String, Object> thresholds) {
        String service = stringOf(params, "serviceName");
        requireServiceInAllowlist(service, "serviceName");

        int cpu = percentOf(thresholds, "cpuWarningPercent", 80);
        int mem = percentOf(thresholds, "memoryWarningPercent", 80);
        int disk = percentOf(thresholds, "diskWarningPercent", 85);
        requirePercentInRange(cpu, "cpuWarningPercent");
        requirePercentInRange(mem, "memoryWarningPercent");
        requirePercentInRange(disk, "diskWarningPercent");
    }

    private void validateDisk(Map<String, Object> params, Map<String, Object> thresholds) {
        String scanDir = stringOf(params, "scanDir");
        if (scanDir == null || scanDir.isBlank()) {
            throw new InspectionValidationException("[scanDir] 不能为空");
        }
        if (!BaseOSValidator.isValidPath(scanDir, BaseOSValidator.ALLOWED_SCAN_ROOTS)) {
            throw new InspectionValidationException(
                    "[scanDir] 路径不在允许的扫描范围内: " + scanDir
                            + " (允许: " + BaseOSValidator.ALLOWED_SCAN_ROOTS + ")");
        }

        Object logSvc = params.get("logServiceName");
        if (logSvc != null && !((String) logSvc).isBlank()) {
            String svc = (String) logSvc;
            requireServiceInAllowlist(svc, "logServiceName");
        }

        int diskWarn = percentOf(thresholds, "diskWarningPercent", 85);
        requirePercentInRange(diskWarn, "diskWarningPercent");

        long largeFileMb = longOf(thresholds, "largeFileMinMb", 1024L);
        if (largeFileMb < 100L || largeFileMb > 1_048_576L) {
            throw new InspectionValidationException(
                    "[largeFileMinMb] 必须在 [100, 1048576] 范围内,当前: " + largeFileMb);
        }
    }

    private void validateService(Map<String, Object> params) {
        String service = stringOf(params, "serviceName");
        requireServiceInAllowlist(service, "serviceName");

        Object portObj = params.get("expectedPort");
        if (portObj != null) {
            int port;
            try {
                port = ((Number) portObj).intValue();
            } catch (ClassCastException e) {
                throw new InspectionValidationException("[expectedPort] 必须是整数,当前: " + portObj);
            }
            if (port < 1 || port > 65535) {
                throw new InspectionValidationException(
                        "[expectedPort] 必须在 [1, 65535] 范围内,当前: " + port);
            }
        }
    }

    // ===== Schedule + timezone =====

    private void validateSchedule(InspectionScheduleType type, InspectionScheduleConfig cfg) {
        if (cfg.localTime() == null) {
            throw new InspectionValidationException("[scheduleConfig.localTime] 不能为空");
        }
        // localTime 严格 HH:mm,通过 LocalTime 自身的正则保证(无效输入会抛 DateTimeException)
        if (type == null) {
            throw new InspectionValidationException("[scheduleType] 不能为空");
        }
        switch (type) {
            case DAILY -> {
                if (cfg.dayOfWeek() != null) {
                    throw new InspectionValidationException(
                            "[dayOfWeek] DAILY 计划不允许设置 dayOfWeek");
                }
                if (cfg.dayOfMonth() != null) {
                    throw new InspectionValidationException(
                            "[dayOfMonth] DAILY 计划不允许设置 dayOfMonth");
                }
            }
            case WEEKLY -> {
                if (cfg.dayOfWeek() == null) {
                    throw new InspectionValidationException(
                            "[dayOfWeek] WEEKLY 计划必须设置 dayOfWeek");
                }
                if (cfg.dayOfMonth() != null) {
                    throw new InspectionValidationException(
                            "[dayOfMonth] WEEKLY 计划不允许设置 dayOfMonth");
                }
            }
            case MONTHLY -> {
                if (cfg.dayOfMonth() == null) {
                    throw new InspectionValidationException(
                            "[dayOfMonth] MONTHLY 计划必须设置 dayOfMonth");
                }
                if (cfg.dayOfMonth() < 1 || cfg.dayOfMonth() > 28) {
                    throw new InspectionValidationException(
                            "[dayOfMonth] 必须在 [1, 28] 范围内(避免 Feb 闰年歧义),当前: "
                                    + cfg.dayOfMonth());
                }
                if (cfg.dayOfWeek() != null) {
                    throw new InspectionValidationException(
                            "[dayOfWeek] MONTHLY 计划不允许设置 dayOfWeek");
                }
            }
        }
    }

    private void validateTimezone(String tz) {
        if (tz == null || tz.isBlank()) {
            throw new InspectionValidationException("[timezone] 不能为空");
        }
        try {
            ZoneId.of(tz);
        } catch (RuntimeException e) {
            throw new InspectionValidationException("[timezone] 不是合法的 IANA 时区: " + tz);
        }
    }

    // ===== Helpers =====

    private void requireServiceInAllowlist(String service, String fieldName) {
        if (service == null || service.isBlank()) {
            throw new InspectionValidationException("[" + fieldName + "] 不能为空");
        }
        if (!BaseOSValidator.isValidServiceName(service)) {
            throw new InspectionValidationException(
                    "[" + fieldName + "] 服务名格式不合法: " + service);
        }
        if (!allowedServices.contains(service)) {
            throw new InspectionValidationException(
                    "[" + fieldName + "] 服务不在巡检允许列表中: " + service
                            + " (允许: " + allowedServices + ")");
        }
    }

    private void requirePercentInRange(int v, String fieldName) {
        if (v < 50 || v > 100) {
            throw new InspectionValidationException(
                    "[" + fieldName + "] 必须在 [50, 100] 范围内,当前: " + v);
        }
    }

    private static String stringOf(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? null : v.toString();
    }

    private static int percentOf(Map<String, Object> m, String key, int dflt) {
        if (m == null) return dflt;
        Object v = m.get(key);
        if (v == null) return dflt;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            throw new InspectionValidationException("[" + key + "] 必须是整数,当前: " + v);
        }
    }

    private static long longOf(Map<String, Object> m, String key, long dflt) {
        if (m == null) return dflt;
        Object v = m.get(key);
        if (v == null) return dflt;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            throw new InspectionValidationException("[" + key + "] 必须是整数,当前: " + v);
        }
    }

    /** 检查 DayOfWeek 是否合法值,防止 WEEKLY 传未知字符串。 */
    public static boolean isValidDayOfWeek(String s) {
        if (s == null) return false;
        try {
            DayOfWeek.valueOf(s.trim().toUpperCase());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}