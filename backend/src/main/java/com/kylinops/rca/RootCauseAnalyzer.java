package com.kylinops.rca;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.tool.ToolResult;

import java.util.List;

/**
 * 根因分析器入口。
 * 委托给具体的 intent-specific analyzer；不适用时返回 null。
 */
public interface RootCauseAnalyzer {

    /**
     * 编排主入口：在 AgentOrchestrator Step 7 后调用。
     *
     * @param intent   识别出的意图
     * @param results  工具执行结果列表
     * @param decision 风险决策
     * @return 根因链；不适用场景返回 null
     */
    RootCauseChain analyze(IntentType intent, List<ToolResult> results, RiskDecision decision);
}
