package com.kylinops.inspection;

import com.kylinops.common.enums.RiskLevel;
import com.kylinops.inspection.model.InspectionTemplateType;
import com.kylinops.tool.ToolDefinition;
import com.kylinops.tool.ToolRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 巡检内置模板注册中心(P1-02 Task 3)。
 *
 * <p>固定三个模板定义,不从 YAML / 数据库加载,设计决策见
 * {@code docs/superpowers/specs/2026-06-18-p1-02-scheduled-inspection-mvp-design.md §6}。
 * 工具元数据(风险等级、权限类型)从 {@link ToolRegistry} 读取,不允许硬编码。</p>
 *
 * <h3>启动期校验</h3>
 * <p>{@link #init()} 阶段为每个模板引用到的工具读取 {@link ToolDefinition} 并断言:</p>
 * <ul>
 *   <li>工具已注册且启用</li>
 *   <li>风险等级 ∈ {L0, L1}</li>
 *   <li>权限类型 == READ</li>
 * </ul>
 * <p>任一不满足则 {@link IllegalStateException} 启动失败 — 这违反 CLAUDE.md 红线 5
 * (不允许硬编码或绕过 L0/L1 + READ 限制)。</p>
 *
 * <h3>DISK 模板的条件扩展</h3>
 * <p>{@code params.logServiceName} 非空时,stage 2 追加 {@code journal_log_tool}。</p>
 */
@Slf4j
@Component
public class InspectionTemplateRegistry {

    private final ToolRegistry toolRegistry;

    private InspectionTemplateDefinition health;
    private InspectionTemplateDefinition disk;
    private InspectionTemplateDefinition service;

    public InspectionTemplateRegistry(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
        // 启动期构建:Spring 构造顺序保证 ToolRegistry 先初始化完毕,这里直接同步构建。
        // 任何风险/权限不满足立即抛 IllegalStateException。
        init();
    }

    /**
     * 暴露为 public 是为了让非 Spring 测试装配路径(POJO + 自定义 ToolRegistry 子类)也能复用。
     * Spring 路径下,构造函数已调用一次,@PostConstruct 会幂等地再调一次(字段非 null 时跳过)。
     */
    @PostConstruct
    public synchronized void init() {
        if (health != null && disk != null && service != null) {
            return; // 幂等:Spring 路径下构造函数已建好
        }
        this.health = buildAndValidate(InspectionTemplateType.HEALTH,
                List.of(
                        List.of("system_info_tool", "cpu_status_tool", "memory_status_tool",
                                "disk_usage_tool", "process_list_tool", "network_port_tool",
                                "service_status_tool"),
                        List.of("journal_log_tool")),
                Set.of("system_info_tool", "cpu_status_tool", "memory_status_tool", "disk_usage_tool"));

        this.disk = buildAndValidate(InspectionTemplateType.DISK,
                List.of(
                        List.of("disk_usage_tool"),
                        List.of("large_file_scan_tool")),
                Set.of("disk_usage_tool", "large_file_scan_tool"));

        this.service = buildAndValidate(InspectionTemplateType.SERVICE,
                List.of(
                        List.of("service_status_tool", "network_port_tool"),
                        List.of("journal_log_tool")),
                Set.of("service_status_tool"));

        log.info("InspectionTemplateRegistry 初始化完成:已注册 HEALTH / DISK / SERVICE 三个内置模板");
    }

    /**
     * 取模板定义。
     *
     * <p>DISK 在 {@code params.logServiceName} 非空时,stage 2 追加 {@code journal_log_tool};
     * 其它模板不依赖 params。</p>
     */
    public Optional<InspectionTemplateDefinition> getTemplate(
            InspectionTemplateType type, Map<String, Object> params) {
        if (type == null) {
            return Optional.empty();
        }
        switch (type) {
            case HEALTH -> {
                return Optional.of(health);
            }
            case SERVICE -> {
                return Optional.of(service);
            }
            case DISK -> {
                if (hasLogService(params)) {
                    return Optional.of(new InspectionTemplateDefinition(
                            List.of(
                                    List.of("disk_usage_tool"),
                                    List.of("large_file_scan_tool"),
                                    List.of("journal_log_tool")),
                            disk.keyToolNames(),
                            disk.riskLevels()));
                }
                return Optional.of(disk);
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    private static boolean hasLogService(Map<String, Object> params) {
        if (params == null) return false;
        Object v = params.get("logServiceName");
        return v != null && !(v instanceof CharSequence) == false && !((CharSequence) v).isEmpty();
    }

    /**
     * 列出所有内置模板(用于 GET /api/inspections/templates,P1-02 Task 7)。
     * 返回 HEALTH / DISK / SERVICE 三个模板的当前定义;DISK 总是返回基础版(无
     * {@code logServiceName} 扩展),与 {@link #getTemplate(type, params)} 的
     * 行为区分(后者是按需展开)。
     */
    public java.util.List<TemplateEntry> getAllTemplates() {
        return java.util.List.of(
                new TemplateEntry(InspectionTemplateType.HEALTH, "系统健康检查", health),
                new TemplateEntry(InspectionTemplateType.DISK, "磁盘诊断", disk),
                new TemplateEntry(InspectionTemplateType.SERVICE, "服务诊断", service)
        );
    }

    /**
     * 模板元数据条目 — 包含 type / 展示名 / 当前定义,controller 转为
     * {@code InspectionTemplateView} 后返回。
     */
    public record TemplateEntry(
            InspectionTemplateType type,
            String displayName,
            InspectionTemplateDefinition definition
    ) {
    }

    /**
     * 构造模板并校验所有引用工具为 L0/L1 + READ + 已注册 + 已启用。
     */
    private InspectionTemplateDefinition buildAndValidate(
            InspectionTemplateType type,
            List<List<String>> stages,
            Set<String> keyTools) {
        Map<String, RiskLevel> riskMap = new HashMap<>();
        for (List<String> stage : stages) {
            for (String toolName : stage) {
                if (!toolRegistry.contains(toolName)) {
                    throw new IllegalStateException(
                            "巡检模板 " + type + " 引用未注册工具: " + toolName);
                }
                ToolDefinition def = toolRegistry.getEnabledToolDefinitions().stream()
                        .filter(d -> d.getToolName().equals(toolName))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "巡检模板 " + type + " 引用未启用工具: " + toolName));

                RiskLevel rl = def.getRiskLevel();
                if (rl != RiskLevel.L0 && rl != RiskLevel.L1) {
                    throw new IllegalStateException(
                            "巡检模板 " + type + " 工具 " + toolName + " 风险等级违规: "
                                    + rl + " (仅允许 L0/L1)");
                }
                if (def.getPermissionType() != com.kylinops.common.enums.PermissionType.READ) {
                    throw new IllegalStateException(
                            "巡检模板 " + type + " 工具 " + toolName + " 权限类型违规: "
                                    + def.getPermissionType() + " (仅允许 READ)");
                }
                riskMap.put(toolName, rl);
            }
        }
        return new InspectionTemplateDefinition(stages, keyTools, riskMap);
    }
}