package com.kylinops.agent;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.rca.RootCauseChain;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Agent 执行结果 POJO
 * <p>
 * 封装 Agent 主流程的完整输出结果，包含回复文本、意图分类、工具调用详情、
 * 风险等级与决策、审计 ID 等。作为 {@link com.kylinops.chat.ChatController}
 * 的响应数据返回前端。
 * </p>
 *
 * <h3>三类响应场景</h3>
 * <ul>
 *   <li><b>ALLOW</b> — 正常执行，包含工具结果和回复</li>
 *   <li><b>CONFIRM</b> — 需要用户确认（L2），包含 PendingAction</li>
 *   <li><b>BLOCK</b> — 被安全规则阻断，展示原因</li>
 * </ul>
 */
@Data
@Builder
public class AgentResult {

    /** 会话 ID */
    private String sessionId;

    /** Agent 回复文本（基于工具结果生成） */
    private String answer;

    /** 识别到的意图类型 */
    private IntentType intentType;

    /** 工具调用详情列表 */
    private List<ToolCallInfo> toolCalls;

    /** 风险等级 */
    private RiskLevel riskLevel;

    /** 风险决策: "ALLOW" | "CONFIRM" | "BLOCK" */
    private String riskDecision;

    /** 是否需要用户确认（L2 操作） */
    private boolean needConfirmation;

    /** L2 待确认动作（当 decision=CONFIRM 时） */
    private PendingAction pendingAction;

    /** 审计日志 ID（贯穿全链路） */
    private String auditId;

    /** 错误信息（仅在异常时填充） */
    private String errorMessage;

    /** 根因分析链（仅演示场景 1/2/3 填充；其他场景为 null） */
    private RootCauseChain rootCauseChain;

    // ==================== 内部类型 ====================

    /**
     * 单次工具调用详情
     */
    @Data
    @Builder
    public static class ToolCallInfo {
        /** 工具名称 */
        private String toolName;

        /** 执行状态: "success" | "failed" | "timeout" | "blocked" */
        private String status;

        /** 中文摘要 */
        private String summary;

        /** 执行耗时（毫秒） */
        private long durationMs;
    }

    /**
     * 待确认动作（L2 用）
     */
    @Data
    @Builder
    public static class PendingAction {
        /** 动作唯一标识 */
        private String actionId;

        /** 目标工具名称 */
        private String toolName;

        /** 调用参数 */
        private Map<String, Object> params;

        /** 人类可读的描述 */
        private String description;
    }
}
