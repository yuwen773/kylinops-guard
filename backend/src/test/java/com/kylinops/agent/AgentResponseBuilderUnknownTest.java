package com.kylinops.agent;

import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentResponseBuilderUnknownTest {

    private final AgentResponseBuilder builder = new AgentResponseBuilder();

    @Test
    void unknown_response_contains_shortcut_suggestions() {
        String r = builder.build(com.kylinops.common.enums.IntentType.UNKNOWN,
                java.util.List.of(), RiskDecision.ALLOW, "test", RiskLevel.L0);
        assertTrue(r.contains("快捷操作建议") || r.contains("检查系统健康状态"));
        assertTrue(r.contains("磁盘快满了"));
        assertTrue(r.contains("检查 nginx 服务"));
        assertTrue(r.contains("查看进程列表"));
        assertTrue(r.contains("查看端口状态"));
        assertTrue(r.contains("查看系统日志"));
        // 必须保留"重新描述你的需求"提示
        assertTrue(r.contains("重新描述") || r.contains("提示"));
    }
}
