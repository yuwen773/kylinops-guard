package com.kylinops.inspection;

import com.kylinops.inspection.model.InspectionScheduleType;
import com.kylinops.inspection.model.InspectionTemplateType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-02 Task 3 — Plan validator boundary tests.
 *
 * <p>Pure POJO tests (no Spring). Builds plan-like value objects and asserts that
 * {@link InspectionPlanValidator#validate(InspectionTemplateType, Map, Map, InspectionScheduleType, InspectionScheduleConfig, String)}
 * accepts legal inputs and rejects illegal ones with {@link InspectionValidationException}
 * whose message names the offending field.</p>
 */
@DisplayName("P1-02 T3 — InspectionPlanValidator boundaries")
class InspectionPlanValidatorTest {

    private InspectionPlanValidator validator;
    private Map<String, Object> defaultAllowedServices;

    @BeforeEach
    void setUp() {
        defaultAllowedServices = Map.of(
                "kylinops.inspection.allowed-services", List.of("nginx"));
        validator = new InspectionPlanValidator(defaultAllowedServices);
    }

    // ===== HEALTH =====

    @Test
    @DisplayName("HEALTH: accepts legal thresholds + service in allowlist")
    void healthAcceptsLegalInputs() {
        Map<String, Object> params = Map.of("serviceName", "nginx");
        Map<String, Object> thresholds = Map.of(
                "cpuWarningPercent", 80,
                "memoryWarningPercent", 85,
                "diskWarningPercent", 90);

        // No throw
        validator.validate(InspectionTemplateType.HEALTH, params, thresholds,
                InspectionScheduleType.DAILY,
                new InspectionScheduleConfig(LocalTime.of(8, 0), null, null),
                "Asia/Shanghai");
    }

    @Test
    @DisplayName("HEALTH: cpuWarningPercent=49 rejected with field-named message")
    void healthRejectsCpuBelowThreshold() {
        Map<String, Object> params = Map.of("serviceName", "nginx");
        Map<String, Object> thresholds = new HashMap<>();
        thresholds.put("cpuWarningPercent", 49);
        thresholds.put("memoryWarningPercent", 80);
        thresholds.put("diskWarningPercent", 80);

        assertThatThrownBy(() -> validator.validate(InspectionTemplateType.HEALTH,
                params, thresholds,
                InspectionScheduleType.DAILY,
                new InspectionScheduleConfig(LocalTime.of(8, 0), null, null),
                "Asia/Shanghai"))
                .isInstanceOf(InspectionValidationException.class)
                .hasMessageContaining("cpuWarningPercent");
    }

    @Test
    @DisplayName("HEALTH: cpuWarningPercent=101 rejected")
    void healthRejectsCpuAboveThreshold() {
        Map<String, Object> params = Map.of("serviceName", "nginx");
        Map<String, Object> thresholds = new HashMap<>();
        thresholds.put("cpuWarningPercent", 101);
        thresholds.put("memoryWarningPercent", 80);
        thresholds.put("diskWarningPercent", 80);

        assertThatThrownBy(() -> validator.validate(InspectionTemplateType.HEALTH,
                params, thresholds,
                InspectionScheduleType.DAILY,
                new InspectionScheduleConfig(LocalTime.of(8, 0), null, null),
                "Asia/Shanghai"))
                .isInstanceOf(InspectionValidationException.class)
                .hasMessageContaining("cpuWarningPercent");
    }

    @Test
    @DisplayName("HEALTH: serviceName not in allowlist rejected")
    void healthRejectsServiceNotInAllowlist() {
        Map<String, Object> params = Map.of("serviceName", "mysql");
        Map<String, Object> thresholds = Map.of(
                "cpuWarningPercent", 80,
                "memoryWarningPercent", 80,
                "diskWarningPercent", 80);

        assertThatThrownBy(() -> validator.validate(InspectionTemplateType.HEALTH,
                params, thresholds,
                InspectionScheduleType.DAILY,
                new InspectionScheduleConfig(LocalTime.of(8, 0), null, null),
                "Asia/Shanghai"))
                .isInstanceOf(InspectionValidationException.class)
                .hasMessageContaining("serviceName");
    }

    // ===== DISK =====

    @Test
    @DisplayName("DISK: accepts /var/log scanDir + null logServiceName")
    void diskAcceptsLegalInputs() {
        Map<String, Object> params = new HashMap<>();
        params.put("scanDir", "/var/log");
        params.put("logServiceName", null);
        Map<String, Object> thresholds = Map.of(
                "diskWarningPercent", 85,
                "largeFileMinMb", 1024);

        validator.validate(InspectionTemplateType.DISK, params, thresholds,
                InspectionScheduleType.WEEKLY,
                new InspectionScheduleConfig(LocalTime.of(8, 0), DayOfWeek.MONDAY, null),
                "UTC");
    }

    @Test
    @DisplayName("DISK: scanDir=/etc rejected (not in BaseOSValidator allowlist)")
    void diskRejectsScanDirNotAllowed() {
        Map<String, Object> params = Map.of("scanDir", "/etc");
        Map<String, Object> thresholds = Map.of(
                "diskWarningPercent", 85,
                "largeFileMinMb", 1024);

        assertThatThrownBy(() -> validator.validate(InspectionTemplateType.DISK,
                params, thresholds,
                InspectionScheduleType.DAILY,
                new InspectionScheduleConfig(LocalTime.of(8, 0), null, null),
                "UTC"))
                .isInstanceOf(InspectionValidationException.class)
                .hasMessageContaining("scanDir");
    }

    @Test
    @DisplayName("DISK: largeFileMinMb=99 rejected (< 100)")
    void diskRejectsLargeFileMinMbTooSmall() {
        Map<String, Object> params = Map.of("scanDir", "/var/log");
        Map<String, Object> thresholds = new HashMap<>();
        thresholds.put("diskWarningPercent", 85);
        thresholds.put("largeFileMinMb", 99);

        assertThatThrownBy(() -> validator.validate(InspectionTemplateType.DISK,
                params, thresholds,
                InspectionScheduleType.DAILY,
                new InspectionScheduleConfig(LocalTime.of(8, 0), null, null),
                "UTC"))
                .isInstanceOf(InspectionValidationException.class)
                .hasMessageContaining("largeFileMinMb");
    }

    @Test
    @DisplayName("DISK: largeFileMinMb=1048577 rejected (> 1048576)")
    void diskRejectsLargeFileMinMbTooLarge() {
        Map<String, Object> params = Map.of("scanDir", "/var/log");
        Map<String, Object> thresholds = new HashMap<>();
        thresholds.put("diskWarningPercent", 85);
        thresholds.put("largeFileMinMb", 1_048_577);

        assertThatThrownBy(() -> validator.validate(InspectionTemplateType.DISK,
                params, thresholds,
                InspectionScheduleType.DAILY,
                new InspectionScheduleConfig(LocalTime.of(8, 0), null, null),
                "UTC"))
                .isInstanceOf(InspectionValidationException.class)
                .hasMessageContaining("largeFileMinMb");
    }

    @Test
    @DisplayName("DISK: logServiceName=mysql rejected when not in allowlist")
    void diskRejectsLogServiceNotInAllowlist() {
        Map<String, Object> params = new HashMap<>();
        params.put("scanDir", "/var/log");
        params.put("logServiceName", "mysql");
        Map<String, Object> thresholds = Map.of(
                "diskWarningPercent", 85,
                "largeFileMinMb", 1024);

        assertThatThrownBy(() -> validator.validate(InspectionTemplateType.DISK,
                params, thresholds,
                InspectionScheduleType.DAILY,
                new InspectionScheduleConfig(LocalTime.of(8, 0), null, null),
                "UTC"))
                .isInstanceOf(InspectionValidationException.class)
                .hasMessageContaining("logServiceName");
    }

    // ===== SERVICE =====

    @Test
    @DisplayName("SERVICE: accepts expectedPort=8080")
    void serviceAcceptsLegalInputs() {
        Map<String, Object> params = new HashMap<>();
        params.put("serviceName", "nginx");
        params.put("expectedPort", 8080);

        validator.validate(InspectionTemplateType.SERVICE, params, new HashMap<>(),
                InspectionScheduleType.DAILY,
                new InspectionScheduleConfig(LocalTime.of(8, 0), null, null),
                "UTC");
    }

    @Test
    @DisplayName("SERVICE: expectedPort=0 rejected")
    void serviceRejectsExpectedPortTooSmall() {
        Map<String, Object> params = new HashMap<>();
        params.put("serviceName", "nginx");
        params.put("expectedPort", 0);

        assertThatThrownBy(() -> validator.validate(InspectionTemplateType.SERVICE,
                params, new HashMap<>(),
                InspectionScheduleType.DAILY,
                new InspectionScheduleConfig(LocalTime.of(8, 0), null, null),
                "UTC"))
                .isInstanceOf(InspectionValidationException.class)
                .hasMessageContaining("expectedPort");
    }

    @Test
    @DisplayName("SERVICE: expectedPort=65536 rejected")
    void serviceRejectsExpectedPortTooLarge() {
        Map<String, Object> params = new HashMap<>();
        params.put("serviceName", "nginx");
        params.put("expectedPort", 65536);

        assertThatThrownBy(() -> validator.validate(InspectionTemplateType.SERVICE,
                params, new HashMap<>(),
                InspectionScheduleType.DAILY,
                new InspectionScheduleConfig(LocalTime.of(8, 0), null, null),
                "UTC"))
                .isInstanceOf(InspectionValidationException.class)
                .hasMessageContaining("expectedPort");
    }

    // ===== Schedule config =====

    @Test
    @DisplayName("WEEKLY + dayOfWeek=null rejected")
    void weeklyRejectsMissingDayOfWeek() {
        Map<String, Object> params = Map.of("serviceName", "nginx");
        Map<String, Object> thresholds = Map.of(
                "cpuWarningPercent", 80, "memoryWarningPercent", 80, "diskWarningPercent", 80);

        assertThatThrownBy(() -> validator.validate(InspectionTemplateType.HEALTH,
                params, thresholds,
                InspectionScheduleType.WEEKLY,
                new InspectionScheduleConfig(LocalTime.of(8, 0), null, null),
                "UTC"))
                .isInstanceOf(InspectionValidationException.class)
                .hasMessageContaining("dayOfWeek");
    }

    @Test
    @DisplayName("MONTHLY + dayOfMonth=29 rejected (Feb leap ambiguity)")
    void monthlyRejectsDayOfMonth29() {
        Map<String, Object> params = Map.of("serviceName", "nginx");
        Map<String, Object> thresholds = Map.of(
                "cpuWarningPercent", 80, "memoryWarningPercent", 80, "diskWarningPercent", 80);

        assertThatThrownBy(() -> validator.validate(InspectionTemplateType.HEALTH,
                params, thresholds,
                InspectionScheduleType.MONTHLY,
                new InspectionScheduleConfig(LocalTime.of(8, 0), null, 29),
                "UTC"))
                .isInstanceOf(InspectionValidationException.class)
                .hasMessageContaining("dayOfMonth");
    }

    @Test
    @DisplayName("timezone=Not/AZone rejected")
    void invalidTimezoneRejected() {
        Map<String, Object> params = Map.of("serviceName", "nginx");
        Map<String, Object> thresholds = Map.of(
                "cpuWarningPercent", 80, "memoryWarningPercent", 80, "diskWarningPercent", 80);

        assertThatThrownBy(() -> validator.validate(InspectionTemplateType.HEALTH,
                params, thresholds,
                InspectionScheduleType.DAILY,
                new InspectionScheduleConfig(LocalTime.of(8, 0), null, null),
                "Not/AZone"))
                .isInstanceOf(InspectionValidationException.class)
                .hasMessageContaining("timezone");
    }

    @Test
    @DisplayName("Empty allowed-services allowlist: any serviceName rejected")
    void emptyAllowlistRejectsAllServices() {
        InspectionPlanValidator strict = new InspectionPlanValidator(Map.of());
        Map<String, Object> params = Map.of("serviceName", "nginx");
        Map<String, Object> thresholds = Map.of(
                "cpuWarningPercent", 80, "memoryWarningPercent", 80, "diskWarningPercent", 80);

        assertThatThrownBy(() -> strict.validate(InspectionTemplateType.HEALTH,
                params, thresholds,
                InspectionScheduleType.DAILY,
                new InspectionScheduleConfig(LocalTime.of(8, 0), null, null),
                "UTC"))
                .isInstanceOf(InspectionValidationException.class)
                .hasMessageContaining("serviceName");
    }
}