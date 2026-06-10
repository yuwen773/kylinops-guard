package com.kylinops.tool;

/**
 * MCP 风格的工具抽象接口
 * <p>
 * 所有 OS 运维能力（系统信息查询、磁盘检查、服务管理等）都实现此接口，
 * 以标准方式被 Agent 发现、调用和审计。
 * </p>
 *
 * <h3>实现约定</h3>
 * <ul>
 *   <li>实现类必须标注 {@link org.springframework.stereotype.Component @Component}，
 *       以便 {@link ToolRegistry} 在启动时自动注册</li>
 *   <li>每个实例必须提供一个固定的 {@link #definition()}，不应在执行时改变</li>
 *   <li>{@link #execute(ToolInput)} 不应自行处理安全校验 — 安全拦截发生在
 *       {@link com.kylinops.security.RiskCheckService} 层</li>
 *   <li>执行中遇到任何异常应直接抛出，由 {@link ToolExecutor} 统一捕获并封装为
 *       {@link ToolResult#failed(String, String, long)}</li>
 * </ul>
 */
public interface OpsTool {

    /**
     * 返回工具的声明式元数据定义。
     * <p>
     * 包含工具名称、描述、输入输出 Schema、风险等级、权限类型、超时时间等。
     * 此信息用于：
     * <ul>
     *   <li>前端「工具中心」页面展示</li>
     *   <li>Agent 意图识别和工具编排</li>
     *   <li>RiskCheckService 安全校验决策</li>
     *   <li>ToolExecutor 超时控制</li>
     * </ul>
     * </p>
     */
    ToolDefinition definition();

    /**
     * 执行工具逻辑。
     * <p>
     * 实现类在此方法中完成具体的 OS 运维操作（如解析 /proc 文件、执行白名单命令等）。
     * 需注意：
     * <ul>
     *   <li>方法内部应接受空参数或缺失参数，降级为合理的默认行为</li>
     *   <li>执行耗时应尽量控制在 {@link ToolDefinition#getTimeoutMs()} 以内</li>
     *   <li>操作结果返回结构化 {@link ToolResult#success(String, Object, String, long)}</li>
     * </ul>
     * </p>
     *
     * @param input 包含工具名称、参数键值对和请求追踪 ID
     * @return 工具执行结果（永远不会返回 null）
     */
    ToolResult execute(ToolInput input);
}
