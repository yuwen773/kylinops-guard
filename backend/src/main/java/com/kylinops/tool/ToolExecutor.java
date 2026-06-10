package com.kylinops.tool;

import com.kylinops.common.enums.ToolCallStatus;
import com.kylinops.common.enums.ToolStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具调用器
 * <p>
 * 负责在安全边界内执行 OpsTool 调用，提供超时控制、异常隔离、
 * 调用记录持久化等横切关注点。
 * </p>
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li><b>安全执行</b> — 所有工具调用通过此执行器，不绕过</li>
 *   <li><b>超时控制</b> — 使用 {@link ExecutorService} + {@link Future#get(long, TimeUnit)}
 *       实现可配置超时</li>
 *   <li><b>异常隔离</b> — 任何异常不抛到上层，统一封装为 {@link ToolResult#failed()}</li>
 *   <li><b>调用审计</b> — 调用前后通过 {@link ToolCallRecordService} 记录完整生命周期</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolExecutor {

    private final ToolRegistry toolRegistry;
    private final ToolCallRecordService recordService;

    /**
     * 工具执行线程池（每个任务一个独立线程）
     */
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tool-executor-");
        t.setDaemon(true);
        return t;
    });

    /**
     * 根据工具名称和参数执行工具。
     * <p>
     * 主入口方法，供 Agent / ChatService 调用。
     * </p>
     *
     * @param toolName  工具名称
     * @param params    调用参数
     * @param requestId 请求追踪 ID（关联 auditId）
     * @return 执行结果（永远不会抛出异常）
     */
    public ToolResult execute(String toolName, Map<String, Object> params, String requestId) {
        // 1. 获取已注册的工具（未注册时抛出 ToolNotRegisteredException）
        OpsTool tool = toolRegistry.getTool(toolName);
        ToolDefinition def = tool.definition();

        // 2. 检查工具是否启用
        if (def.getToolStatus() != ToolStatus.ENABLED) {
            return ToolResult.blocked(toolName, "工具已被禁用: " + toolName, 0);
        }

        // 3. 执行
        return execute(tool, ToolInput.builder()
                .toolName(toolName)
                .params(params)
                .requestId(requestId)
                .build());
    }

    /**
     * 执行已获取的 OpsTool 实例。
     * <p>
     * 包含完整的超时控制和异常隔离逻辑。
     * </p>
     *
     * @param tool  工具实例
     * @param input 调用输入
     * @return 执行结果（永远不会抛出异常）
     */
    public ToolResult execute(OpsTool tool, ToolInput input) {
        ToolDefinition def = tool.definition();
        String toolName = def.getToolName();
        long timeoutMs = def.getTimeoutMs();

        // 1. 创建调用记录（PENDING）
        ToolCallRecord record = createPendingRecord(input);
        String toolCallId = record.getToolCallId();
        log.info("开始执行工具: toolName={}, toolCallId={}, timeout={}ms", toolName, toolCallId, timeoutMs);

        // 2. 标记 RUNNING
        record.setStatus(ToolCallStatus.RUNNING);
        recordService.updateRecord(record);

        // 3. 异步执行
        long startTime = System.currentTimeMillis();
        Future<ToolResult> future = executor.submit(() -> {
            Thread.currentThread().setName("tool-" + toolName + "-" + toolCallId);
            return tool.execute(input);
        });

        ToolResult result;
        try {
            result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            long duration = System.currentTimeMillis() - startTime;

            if (result == null) {
                result = ToolResult.failed(toolName, "工具返回 null 结果", duration);
            } else {
                // 确保 result 中的耗时准确
                if (result.getDurationMs() <= 0) {
                    result.setDurationMs(duration);
                }
                result.setStartedAt(record.getCreatedAt());
                result.setFinishedAt(LocalDateTime.now());
            }

            // 4a. 记录成功
            if ("success".equals(result.getStatus())) {
                recordService.markSuccess(toolCallId, result);
                log.info("工具执行成功: toolName={}, toolCallId={}, duration={}ms",
                        toolName, toolCallId, result.getDurationMs());
            } else {
                recordService.markFailed(toolCallId, ToolCallStatus.FAILED, result);
                log.warn("工具执行返回失败: toolName={}, toolCallId={}, error={}",
                        toolName, toolCallId, result.getErrorMessage());
            }

        } catch (TimeoutException e) {
            future.cancel(true);
            long duration = System.currentTimeMillis() - startTime;
            result = ToolResult.timeout(toolName, timeoutMs);
            result.setDurationMs(duration);
            recordService.markFailed(toolCallId, ToolCallStatus.TIMEOUT, result);
            log.warn("工具执行超时: toolName={}, toolCallId={}, timeout={}ms",
                    toolName, toolCallId, timeoutMs);

        } catch (Exception e) {
            future.cancel(true);
            long duration = System.currentTimeMillis() - startTime;
            result = ToolResult.failed(toolName, "工具执行异常: " + e.getMessage(), duration);
            recordService.markFailed(toolCallId, ToolCallStatus.FAILED, result);
            log.error("工具执行异常: toolName={}, toolCallId={}", toolName, toolCallId, e);
        }

        return result;
    }

    // ==================== 内部方法 ====================

    /**
     * 创建 PENDING 状态的调用记录
     */
    private ToolCallRecord createPendingRecord(ToolInput input) {
        ToolCallRecord record = new ToolCallRecord();
        record.setToolCallId(UUID.randomUUID().toString());
        record.setToolName(input.getToolName());
        record.setInput(toJson(input.getParams()));
        record.setStatus(ToolCallStatus.PENDING);
        record.setAuditId(input.getRequestId());
        // message 设为 null（独立工具调用；Chat 流程会自动关联）
        record.setMessage(null);
        return recordService.createRecord(record);
    }

    /**
     * 将参数对象转为 JSON 字符串
     */
    private String toJson(Object obj) {
        if (obj == null) return "{}";
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }
}
