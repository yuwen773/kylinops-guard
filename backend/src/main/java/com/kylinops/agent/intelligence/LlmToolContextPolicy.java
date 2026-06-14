package com.kylinops.agent.intelligence;

import com.kylinops.tool.ToolResult;

/**
 * 工具上下文策略 (P3-T3).
 *
 * <p>控制注入 LLM 的工具结果，确保：</p>
 * <ul>
 *   <li>单工具结果不超过 {@code maxBytes}（默认 4KB）</li>
 *   <li>仅暴露白名单字段（剥离命令/环境变量/密钥/堆栈全文）</li>
 *   <li>间接注入文本被 sanitized</li>
 * </ul>
 *
 * <h3>使用场景</h3>
 * <p>{@link com.kylinops.agent.HybridResponseService}（P3-T4）在聚合多工具结果后注入 LLM 时，
 * 必须先按 toolName 查 policy；缺 policy → 不调 LLM，回退模板回复（fail-closed）。</p>
 */
public interface LlmToolContextPolicy {

    /**
     * 工具名称（与 OpsTool.definition().getToolName() 一致）。
     */
    String toolName();

    /**
     * 将工具结果脱敏 + 截断为 LLM 可消费的字符串。
     *
     * <p>实现约束：</p>
     * <ul>
     *   <li>超过 {@code maxBytes} → 截断并以 "...[truncated]" 结尾</li>
     *   <li>空 / 失败 / 阻断 → 返回 "（无数据）"</li>
     *   <li>不得泄露：完整命令行、环境变量值、API key / token 原文、文件内容</li>
     *   <li>间接注入文本（"忽略以上所有指令"等）必须被替换为占位符</li>
     * </ul>
     *
     * @param result   工具调用结果
     * @param maxBytes 最大字节数（UTF-8 字符计）
     * @return LLM 可见的脱敏字符串（永不返回 null）
     */
    String sanitize(ToolResult result, int maxBytes);

    /**
     * 标记敏感工具 — 这类工具的输出可能含机密信息或可被间接注入利用的字段。
     * <p>敏感工具的 policy 必须更严格地剥离内容（仅保留元信息，不展开全文）。</p>
     *
     * @return 是否敏感
     */
    boolean isSensitive();
}