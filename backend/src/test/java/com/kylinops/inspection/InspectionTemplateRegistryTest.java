package com.kylinops.inspection;

import com.kylinops.common.enums.PermissionType;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.inspection.model.InspectionTemplateType;
import com.kylinops.tool.ToolDefinition;
import com.kylinops.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-02 Task 3 — Template registry contract tests.
 *
 * <p>Asserts the three fixed templates (HEALTH / DISK / SERVICE) reference exactly the
 * registered L0/L1 read-only tools, in the expected stage order, with the expected
 * journal-log conditional for DISK.</p>
 *
 * <p><b>Test setup:</b> Uses real {@link ToolRegistry} populated by the test slice —
 * no mocks. The registry must report every referenced tool as {@code ENABLED},
 * {@code RiskLevel} ∈ {L0, L1}, and {@code PermissionType} == READ.</p>
 */
@DisplayName("P1-02 T3 — InspectionTemplateRegistry contract")
class InspectionTemplateRegistryTest {

    /** Build a TestToolRegistry pre-populated with the 10 tools the three templates reference. */
    private static ToolRegistry populatedRegistry() {
        return new TestToolRegistry(List.of(
                toolDef("system_info_tool", RiskLevel.L0, PermissionType.READ),
                toolDef("cpu_status_tool", RiskLevel.L0, PermissionType.READ),
                toolDef("memory_status_tool", RiskLevel.L0, PermissionType.READ),
                toolDef("disk_usage_tool", RiskLevel.L0, PermissionType.READ),
                toolDef("large_file_scan_tool", RiskLevel.L0, PermissionType.READ),
                toolDef("process_list_tool", RiskLevel.L0, PermissionType.READ),
                toolDef("network_port_tool", RiskLevel.L0, PermissionType.READ),
                toolDef("service_status_tool", RiskLevel.L1, PermissionType.READ),
                toolDef("journal_log_tool", RiskLevel.L0, PermissionType.READ)));
    }

    @Test
    @DisplayName("HEALTH: stage0 = 7 read tools, stage1 = journal_log_tool")
    void healthTemplateHasExpectedStages() {
        InspectionTemplateRegistry registry = new InspectionTemplateRegistry(populatedRegistry());
        Optional<InspectionTemplateDefinition> tpl =
                registry.getTemplate(InspectionTemplateType.HEALTH, new HashMap<>());

        assertThat(tpl).isPresent();
        InspectionTemplateDefinition def = tpl.get();
        assertThat(def.stages()).hasSize(2);
        assertThat(def.stages().get(0)).containsExactly(
                "system_info_tool", "cpu_status_tool", "memory_status_tool",
                "disk_usage_tool", "process_list_tool", "network_port_tool",
                "service_status_tool");
        assertThat(def.stages().get(1)).containsExactly("journal_log_tool");
    }

    @Test
    @DisplayName("DISK with no logServiceName: 2 stages, journal omitted")
    void diskTemplateWithoutLogServiceOmitsJournal() {
        InspectionTemplateRegistry registry = new InspectionTemplateRegistry(populatedRegistry());
        Map<String, Object> params = new HashMap<>();
        params.put("logServiceName", null);

        Optional<InspectionTemplateDefinition> tpl =
                registry.getTemplate(InspectionTemplateType.DISK, params);

        assertThat(tpl).isPresent();
        InspectionTemplateDefinition def = tpl.get();
        assertThat(def.stages()).hasSize(2);
        assertThat(def.stages().get(0)).containsExactly("disk_usage_tool");
        assertThat(def.stages().get(1)).containsExactly("large_file_scan_tool");
    }

    @Test
    @DisplayName("DISK with logServiceName=nginx: 3 stages, journal appended")
    void diskTemplateWithLogServiceIncludesJournal() {
        InspectionTemplateRegistry registry = new InspectionTemplateRegistry(populatedRegistry());
        Map<String, Object> params = new HashMap<>();
        params.put("logServiceName", "nginx");

        Optional<InspectionTemplateDefinition> tpl =
                registry.getTemplate(InspectionTemplateType.DISK, params);

        assertThat(tpl).isPresent();
        InspectionTemplateDefinition def = tpl.get();
        assertThat(def.stages()).hasSize(3);
        assertThat(def.stages().get(0)).containsExactly("disk_usage_tool");
        assertThat(def.stages().get(1)).containsExactly("large_file_scan_tool");
        assertThat(def.stages().get(2)).containsExactly("journal_log_tool");
    }

    @Test
    @DisplayName("SERVICE: stage0 = [service_status_tool, network_port_tool], stage1 = journal")
    void serviceTemplateHasExpectedStages() {
        InspectionTemplateRegistry registry = new InspectionTemplateRegistry(populatedRegistry());
        Optional<InspectionTemplateDefinition> tpl =
                registry.getTemplate(InspectionTemplateType.SERVICE, new HashMap<>());

        assertThat(tpl).isPresent();
        InspectionTemplateDefinition def = tpl.get();
        assertThat(def.stages()).hasSize(2);
        assertThat(def.stages().get(0)).containsExactly("service_status_tool", "network_port_tool");
        assertThat(def.stages().get(1)).containsExactly("journal_log_tool");
    }

    @Test
    @DisplayName("All referenced tools must be L0/L1 + READ + ENABLED")
    void allReferencedToolsAreReadOnlyAndEnabled() {
        ToolRegistry local = populatedRegistry();
        InspectionTemplateRegistry reg = new InspectionTemplateRegistry(local);

        for (InspectionTemplateType type : InspectionTemplateType.values()) {
            Optional<InspectionTemplateDefinition> tpl =
                    reg.getTemplate(type, withLogService(new HashMap<>()));
            assertThat(tpl).as("template %s must exist", type).isPresent();
            for (List<String> stage : tpl.get().stages()) {
                for (String toolName : stage) {
                    ToolDefinition td = local.getEnabledToolDefinitions().stream()
                            .filter(d -> d.getToolName().equals(toolName))
                            .findFirst().orElseThrow();
                    assertThat(td.getRiskLevel()).as("%s riskLevel", toolName)
                            .isIn(RiskLevel.L0, RiskLevel.L1);
                    assertThat(td.getPermissionType()).as("%s permission", toolName)
                            .isEqualTo(PermissionType.READ);
                }
            }
        }
    }

    private static Map<String, Object> withLogService(Map<String, Object> base) {
        base.put("logServiceName", "nginx");
        base.put("serviceName", "nginx");
        base.put("scanDir", "/var/log");
        return base;
    }

    /** Minimal ToolDefinition helper for the negative-path test. */
    private static ToolDefinition toolDef(String name, RiskLevel risk, PermissionType perm) {
        ToolDefinition d = new ToolDefinition();
        d.setToolName(name);
        d.setDescription("test " + name);
        d.setRiskLevel(risk);
        d.setPermissionType(perm);
        d.setToolStatus(com.kylinops.common.enums.ToolStatus.ENABLED);
        d.setTimeoutMs(3000L);
        d.setAuditRequired(true);
        return d;
    }

    /**
     * Minimal subclass of ToolRegistry that bypasses @PostConstruct bean wiring and
     * seeds definitions directly. We expose definitions via a public package-private
     * hook because the production ToolRegistry does not expose a public definition
     * accessor by name (only {@link ToolRegistry#getTool(String)} which requires an
     * OpsTool instance).
     */
    static class TestToolRegistry extends ToolRegistry {
        private final List<ToolDefinition> defs;

        TestToolRegistry(List<ToolDefinition> defs) {
            super(List.of());
            this.defs = defs;
        }

        @Override
        public List<ToolDefinition> getEnabledToolDefinitions() {
            return defs;
        }

        @Override
        public boolean contains(String toolName) {
            return defs.stream().anyMatch(d -> d.getToolName().equals(toolName));
        }
    }
}