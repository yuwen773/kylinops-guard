package com.kylinops.tool;

import com.kylinops.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 工具管理 REST 控制器
 * <p>
 * 提供工具注册中心的查询接口，供前端「工具中心」页面使用。
 * </p>
 *
 * <h3>接口列表</h3>
 * <ul>
 *   <li>{@code GET /api/tools} — 返回所有已注册工具（含已禁用）</li>
 *   <li>{@code GET /api/tools/{toolName}} — 返回单个工具详情</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class ToolController {

    private final ToolRegistry toolRegistry;

    /**
     * 获取所有已注册工具列表
     * <p>
     * 返回完整的工具定义元数据，包含风险等级、权限类型、启用状态等。
     * 前端工具中心页面通过此接口展示所有可用工具。
     * </p>
     */
    @GetMapping
    public ApiResponse<List<ToolDefinitionVO>> listTools() {
        List<ToolDefinitionVO> tools = toolRegistry.getAllToolDefinitions().stream()
                .map(ToolDefinitionVO::fromEntity)
                .collect(Collectors.toList());
        log.debug("查询工具列表: 共 {} 个工具", tools.size());
        return ApiResponse.success(tools);
    }

    /**
     * 获取单个工具详情
     *
     * @param toolName 工具名称
     */
    @GetMapping("/{toolName}")
    public ApiResponse<ToolDefinitionVO> getTool(@PathVariable String toolName) {
        OpsTool tool = toolRegistry.getTool(toolName);
        ToolDefinitionVO vo = ToolDefinitionVO.fromEntity(tool.definition());
        return ApiResponse.success(vo);
    }
}
