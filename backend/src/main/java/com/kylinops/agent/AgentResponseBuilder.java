package com.kylinops.agent;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent 回复构建器
 * <p>
 * 根据意图类型和工具执行结果，生成结构化的中文回复文本。
 * P0 阶段使用模板规则生成，预留 LLM 接入接口。
 * </p>
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li>回复必须基于工具结果，不能编造</li>
 *   <li>执行失败的工具要在回复中说明</li>
 *   <li>阻断场景要清晰展示原因</li>
 *   <li>L2 CONFIRM 场景要说明待确认动作</li>
 * </ul>
 */
@Slf4j
@Component
public class AgentResponseBuilder {

    /**
     * 根据意图类型和工具结果生成回复文本。
     *
     * @param intent    意图类型
     * @param results   工具执行结果列表
     * @param decision  风险决策
     * @param riskReason 风险原因（阻断/确认时使用）
     * @param riskLevel 风险等级
     * @return 结构化的中文回复文本
     */
    public String build(IntentType intent, List<ToolResult> results,
                        RiskDecision decision, String riskReason, RiskLevel riskLevel) {

        // BLOCK — 阻断场景
        if (decision == RiskDecision.BLOCK) {
            return buildBlockedResponse(riskReason, riskLevel);
        }

        // CONFIRM — 确认场景
        if (decision == RiskDecision.CONFIRM) {
            return buildConfirmResponse(results);
        }

        // 正常场景 — 按意图选择模板
        switch (intent) {
            case SYSTEM_CHECK:
                return buildSystemCheckResponse(results);
            case DISK_DIAGNOSIS:
                return buildDiskDiagnosisResponse(results);
            case SERVICE_DIAGNOSIS:
                return buildServiceDiagnosisResponse(results);
            case PROCESS_QUERY:
                return buildProcessQueryResponse(results);
            case NETWORK_QUERY:
                return buildNetworkQueryResponse(results);
            case LOG_QUERY:
                return buildLogQueryResponse(results);
            case FILE_OPERATION:
                return buildFileOperationResponse(results);
            case COMMAND_EXECUTION:
                return buildCommandExecutionResponse(results);
            case GENERAL_CHAT:
                return buildGeneralChatResponse();
            case UNKNOWN:
            default:
                return buildUnknownResponse();
        }
    }

    // ==================== 系统健康检查 ====================

    @SuppressWarnings("unchecked")
    private String buildSystemCheckResponse(List<ToolResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("系统健康检查完成\n");
        sb.append("═══════════════════════════════════════\n\n");

        long successCount = 0;
        long totalCount = results.size();

        for (ToolResult result : results) {
            boolean isSuccess = result.isSuccess();
            if (isSuccess) successCount++;
            String icon = isSuccess ? "✅" : "❌";
            String summary = result.getSummary() != null ? result.getSummary() : result.getStatus();
            sb.append(icon).append(" ").append(formatToolName(result.getToolName())).append(": ")
                    .append(summary).append("\n");

            // 失败时补充错误信息
            if (!isSuccess && result.getErrorMessage() != null) {
                sb.append("   └─ ").append(result.getErrorMessage()).append("\n");
            }

            // 成功时尝试展示摘要数据
            if (isSuccess && result.getData() instanceof Map) {
                Map<String, Object> data = (Map<String, Object>) result.getData();
                if (data.containsKey("summary")) {
                    sb.append("   └─ ").append(data.get("summary")).append("\n");
                }
            }
        }

        sb.append("\n═══════════════════════════════════════\n");
        int healthScore = totalCount > 0 ? (int) (successCount * 100 / totalCount) : 0;
        sb.append("健康评分: ").append(healthScore).append("/100（")
                .append(successCount).append("/").append(totalCount).append(" 项正常）\n\n");

        if (healthScore >= 80) {
            sb.append("📊 结论: 系统运行状态良好，各项指标正常。\n");
        } else if (healthScore >= 60) {
            sb.append("📊 结论: 系统存在部分异常，建议进一步排查以下问题项。\n");
        } else {
            sb.append("📊 结论: 系统存在较多异常，请及时处理。\n");
        }

        return sb.toString();
    }

    // ==================== 磁盘诊断 ====================

    @SuppressWarnings("unchecked")
    private String buildDiskDiagnosisResponse(List<ToolResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("磁盘分析完成\n");
        sb.append("═══════════════════════════════════════\n\n");

        boolean hasFailure = false;
        for (ToolResult result : results) {
            if (!result.isSuccess()) {
                sb.append("❌ ").append(formatToolName(result.getToolName())).append(": 执行失败")
                        .append(result.getErrorMessage() != null ? " - " + result.getErrorMessage() : "")
                        .append("\n\n");
                hasFailure = true;
                continue;
            }
            sb.append(result.getSummary()).append("\n");

            // 尝试提取详细数据
            if (result.getData() instanceof Map) {
                Map<String, Object> data = (Map<String, Object>) result.getData();

                // 分区信息
                if (data.containsKey("partitions")) {
                    Object partitions = data.get("partitions");
                    sb.append("分区使用详情:\n");
                    if (partitions instanceof List) {
                        List<Object> partitionList = (List<Object>) partitions;
                        for (Object p : partitionList) {
                            sb.append("  • ").append(p).append("\n");
                        }
                    } else {
                        sb.append("  ").append(partitions).append("\n");
                    }
                }

                // 大文件信息
                if (data.containsKey("largeFiles") || data.containsKey("files")) {
                    Object files = data.getOrDefault("largeFiles", data.get("files"));
                    sb.append("大文件 Top:\n");
                    if (files instanceof List) {
                        List<Object> fileList = (List<Object>) files;
                        int limit = Math.min(5, fileList.size());
                        for (int i = 0; i < limit; i++) {
                            sb.append("  ").append(i + 1).append(". ").append(fileList.get(i)).append("\n");
                        }
                        if (fileList.size() > limit) {
                            sb.append("  ... 共 ").append(fileList.size()).append(" 个大文件\n");
                        }
                    } else {
                        sb.append("  ").append(files).append("\n");
                    }
                }
            }
            sb.append("\n");
        }

        sb.append("═══════════════════════════════════════\n");
        sb.append("💡 清理建议:\n");
        sb.append("  1. 临时文件（/tmp、/var/tmp）可安全清理\n");
        sb.append("  2. 应用日志文件（/var/log）可考虑日志轮转\n");
        sb.append("  3. 缓存文件确认无业务依赖后清理\n");
        if (!hasFailure) {
            sb.append("  4. ⚠️ 任何清理操作需确认后方可执行\n");
        }

        return sb.toString();
    }

    // ==================== 服务诊断 ====================

    private String buildServiceDiagnosisResponse(List<ToolResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("服务诊断完成\n");
        sb.append("═══════════════════════════════════════\n\n");

        for (ToolResult result : results) {
            String icon = result.isSuccess() ? "✅" : "❌";
            String summary = result.getSummary() != null ? result.getSummary() : result.getStatus();
            sb.append(icon).append(" ").append(formatToolName(result.getToolName())).append(": ")
                    .append(summary).append("\n");
            if (!result.isSuccess() && result.getErrorMessage() != null) {
                sb.append("   └─ ").append(result.getErrorMessage()).append("\n");
            }
        }

        sb.append("\n═══════════════════════════════════════\n");
        sb.append("💡 如需重启服务，请告知服务名称。重启操作需要二次确认。\n");

        return sb.toString();
    }

    // ==================== 进程查询 ====================

    private String buildProcessQueryResponse(List<ToolResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("进程查询结果\n");
        sb.append("═══════════════════════════════════════\n\n");
        for (ToolResult result : results) {
            if (!result.isSuccess()) {
                sb.append("❌ 进程查询失败").append(result.getErrorMessage() != null ? ": " + result.getErrorMessage() : "").append("\n");
                continue;
            }
            sb.append(result.getSummary()).append("\n");
        }
        return sb.toString();
    }

    // ==================== 网络查询 ====================

    private String buildNetworkQueryResponse(List<ToolResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("网络状态查询结果\n");
        sb.append("═══════════════════════════════════════\n\n");
        for (ToolResult result : results) {
            if (!result.isSuccess()) {
                sb.append("❌ ").append(formatToolName(result.getToolName())).append(": 执行失败\n");
                continue;
            }
            sb.append(result.getSummary()).append("\n");
        }
        return sb.toString();
    }

    // ==================== 日志查询 ====================

    private String buildLogQueryResponse(List<ToolResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("日志查询结果\n");
        sb.append("═══════════════════════════════════════\n\n");
        for (ToolResult result : results) {
            if (!result.isSuccess()) {
                sb.append("❌ 日志查询失败").append(result.getErrorMessage() != null ? ": " + result.getErrorMessage() : "").append("\n");
                continue;
            }
            sb.append(result.getSummary()).append("\n");
        }
        return sb.toString();
    }

    // ==================== 文件操作 ====================

    private String buildFileOperationResponse(List<ToolResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("文件分析完成\n");
        sb.append("═══════════════════════════════════════\n\n");
        for (ToolResult result : results) {
            if (!result.isSuccess()) {
                sb.append("❌ ").append(formatToolName(result.getToolName())).append(": 执行失败")
                        .append(result.getErrorMessage() != null ? " - " + result.getErrorMessage() : "").append("\n\n");
                continue;
            }
            sb.append(result.getSummary()).append("\n");
        }
        sb.append("═══════════════════════════════════════\n");
        sb.append("⚠️ 文件操作需要确认后才能执行，请确认后续操作。\n");
        return sb.toString();
    }

    // ==================== 命令执行 ====================

    private String buildCommandExecutionResponse(List<ToolResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("命令执行风险检查结果\n");
        sb.append("═══════════════════════════════════════\n\n");
        for (ToolResult result : results) {
            if (!result.isSuccess()) {
                sb.append("❌ ").append(result.getSummary()).append("\n");
                continue;
            }
            sb.append(result.getSummary()).append("\n");
        }
        return sb.toString();
    }

    // ==================== 通用对话 ====================

    private String buildGeneralChatResponse() {
        return "你好！我是麒麟安全智能运维 Agent（KylinOps Guard），你的系统运维助手 🛡️\n\n"
                + "我可以帮助你执行以下运维操作：\n\n"
                + "🔹 **系统健康检查** — 全面检查 CPU、内存、磁盘、网络等状态\n"
                + "🔹 **磁盘诊断** — 分析磁盘使用情况，提供安全清理建议\n"
                + "🔹 **服务检查** — 检查服务运行状态、端口监听情况\n"
                + "🔹 **进程查询** — 查看进程列表或特定进程详情\n"
                + "🔹 **网络查询** — 查看端口监听和网络连接状态\n"
                + "🔹 **日志查询** — 查看系统日志和应用日志\n\n"
                + "请告诉我你需要什么帮助？";
    }

    // ==================== 未识别意图 ====================

    private String buildUnknownResponse() {
        return "抱歉，我没能理解你的意图 🤔\n\n"
                + "你可以尝试以下指令：\n\n"
                + "🔹 \"检查系统健康状态\" — 全面系统巡检\n"
                + "🔹 \"磁盘快满了\" — 磁盘使用分析\n"
                + "🔹 \"检查 nginx 服务\" — 服务状态诊断\n"
                + "🔹 \"查看进程列表\" — 进程查询\n"
                + "🔹 \"查看端口状态\" — 网络端口检查\n"
                + "🔹 \"查看系统日志\" — 日志查询\n\n"
                + "请重新描述你的需求。";
    }

    // ==================== 阻断 ====================

    private String buildBlockedResponse(String reason, RiskLevel riskLevel) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ 安全拦截\n\n");
        sb.append("系统已拦截该请求，原因如下:\n\n");
        sb.append("🔴 风险等级: ").append(riskLevel != null ? riskLevel.name() : "N/A").append("\n");
        sb.append("📋 拦截原因: ").append(reason != null ? reason : "未知").append("\n\n");
        sb.append("═══════════════════════════════════════\n");
        sb.append("安全提示:\n");
        sb.append("  • 系统已记录本次操作到审计日志\n");
        sb.append("  • 如有疑问，请联系系统管理员\n");

        return sb.toString();
    }

    // ==================== 确认 ====================

    private String buildConfirmResponse(List<ToolResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ 该操作需要您确认后才能执行\n\n");
        sb.append("═══════════════════════════════════════\n\n");

        for (ToolResult result : results) {
            if (result.isSuccess()) {
                sb.append("✅ ").append(formatToolName(result.getToolName())).append(": ")
                        .append(result.getSummary()).append("\n");
            }
        }

        sb.append("\n═══════════════════════════════════════\n");
        sb.append("请在确认后执行操作。确认后系统将执行相应修改。\n");

        return sb.toString();
    }

    // ==================== 工具方法 ====================

    /**
     * 将工具名（snake_case）格式化为更可读的形式
     */
    private String formatToolName(String toolName) {
        if (toolName == null) return "未知工具";
        return toolName.replace("_tool", "")
                .replace("_", " ")
                .trim();
    }
}
