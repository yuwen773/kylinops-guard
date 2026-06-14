package com.kylinops.llm;

import java.util.List;

/**
 * LLM 调用接口。
 *
 * <p>P3-T1 引入的薄抽象层，使 Agent 模块可以在不直接依赖
 * 具体 HTTP 客户端的前提下注入 LLM 能力。当 {@code kylinops.llm.enabled=false}
 * 或容器中没有 LlmClient bean 时，Agent 模块必须降级到规则匹配 ——
 * LLM 调用失败/缺失不得阻塞主链路。</p>
 *
 * <p>调用方契约：</p>
 * <ul>
 *   <li>调用方负责传入 {@link LlmStage}，由实现按阶段选择超时</li>
 *   <li>调用方负责处理 {@link LlmClientException}，区分 reason 决定降级策略</li>
 *   <li>调用方不得假定 LLM 返回为空即安全 — 实现统一返回 content="" 或抛 INVALID_RESPONSE</li>
 * </ul>
 */
public interface LlmClient {

    /**
     * 调用 LLM 完成一次对话补全。
     *
     * @param stage    当前调用阶段（意图 / 响应）
     * @param messages 多轮对话历史
     * @return LLM 返回的内容
     * @throws LlmClientException 任何失败（网络、超时、4xx/5xx、响应畸形）
     */
    LlmCallResult complete(LlmStage stage, List<ChatMessage> messages);
}