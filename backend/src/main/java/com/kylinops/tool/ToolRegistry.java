package com.kylinops.tool;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具注册中心
 * <p>
 * 管理所有 OpsTool 实例的生命周期和查询。启动时自动扫描并注册容器中所有
 * {@link OpsTool} 类型的 Spring Bean。
 * </p>
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li><b>启动注册</b> — 容器启动后自动扫描所有 {@link Component @Component} 的 OpsTool</li>
 *   <li><b>按名查询</b> — 根据 toolName 获取对应的 OpsTool 实例</li>
 *   <li><b>枚举工具</b> — 返回全部/已启用的工具定义列表（供前端工具中心展示）</li>
 *   <li><b>安全性</b> — 禁止 Agent 直接调用未注册工具，未注册工具抛出 {@link ToolNotRegisteredException}</li>
 * </ul>
 */
@Slf4j
@Component
public class ToolRegistry {

    /** 工具名称 → OpsTool 实例（线程安全） */
    private final Map<String, OpsTool> toolMap = new ConcurrentHashMap<>();

    private final List<OpsTool> toolBeans;

    /**
     * 构造注入所有 OpsTool 类型的 Spring Bean
     */
    public ToolRegistry(List<OpsTool> toolBeans) {
        this.toolBeans = toolBeans;
    }

    /**
     * 初始化：自动注册所有 OpsTool Bean
     */
    @PostConstruct
    public void init() {
        for (OpsTool tool : toolBeans) {
            register(tool);
        }
        log.info("ToolRegistry 初始化完成: 共注册 {} 个工具", count());
        if (count() > 0) {
            log.debug("已注册工具: {}", toolMap.keySet());
        }
    }

    /**
     * 注册一个工具实例。
     * <p>
     * 如果同名工具已存在，则用新实例覆盖（并记录警告）。
     * </p>
     *
     * @param tool 工具实例
     */
    public void register(OpsTool tool) {
        ToolDefinition def = tool.definition();
        String toolName = def.getToolName();
        OpsTool existing = toolMap.put(toolName, tool);
        if (existing != null) {
            log.warn("工具 [{}] 被重新注册（覆盖旧实例）", toolName);
        } else {
            log.debug("注册工具: {} (风险等级={}, 权限类型={})",
                    toolName, def.getRiskLevel(), def.getPermissionType());
        }
    }

    /**
     * 根据工具名称获取工具实例。
     *
     * @param toolName 工具名称（区分大小写）
     * @return OpsTool 实例
     * @throws ToolNotRegisteredException 如果工具未注册
     */
    public OpsTool getTool(String toolName) {
        OpsTool tool = toolMap.get(toolName);
        if (tool == null) {
            throw new ToolNotRegisteredException(toolName);
        }
        // 检查工具是否被禁用
        if (tool.definition().getToolStatus() == com.kylinops.common.enums.ToolStatus.DISABLED) {
            throw new ToolNotRegisteredException(toolName,
                    "工具已被禁用: " + toolName + "，请先在系统设置中启用");
        }
        return tool;
    }

    /**
     * 判断工具是否存在（无论启用还是禁用）
     */
    public boolean contains(String toolName) {
        return toolMap.containsKey(toolName);
    }

    /**
     * 返回所有工具的元数据定义列表（含已禁用的工具）
     */
    public List<ToolDefinition> getAllToolDefinitions() {
        return toolMap.values().stream()
                .map(OpsTool::definition)
                .collect(Collectors.toList());
    }

    /**
     * 返回所有已启用工具的元数据定义列表
     */
    public List<ToolDefinition> getEnabledToolDefinitions() {
        return toolMap.values().stream()
                .map(OpsTool::definition)
                .filter(def -> def.getToolStatus() == com.kylinops.common.enums.ToolStatus.ENABLED)
                .collect(Collectors.toList());
    }

    /**
     * 返回已注册工具的总数
     */
    public int count() {
        return toolMap.size();
    }

    /**
     * 返回所有工具名称（不可变视图）
     */
    public List<String> getToolNames() {
        return Collections.unmodifiableList(
                toolMap.keySet().stream().sorted().collect(Collectors.toList())
        );
    }
}
