package com.kylinops.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kylinops.common.enums.ToolCallStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 工具调用记录服务
 * <p>
 * 负责 {@link ToolCallRecord} 实体的写入和查询。
 * {@link ToolExecutor} 在工具调用前后通过此服务记录完整生命周期。
 * </p>
 *
 * <h3>记录状态变迁</h3>
 * <pre>
 * PENDING → RUNNING → SUCCESS
 *                    → FAILED
 *                    → TIMEOUT
 *                    → BLOCKED
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolCallRecordService {

    private final ToolCallRecordRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * 创建一条新的工具调用记录（初始状态 PENDING）
     *
     * @param record 已填充基本字段的记录（toolCallId, toolName, input, auditId 等）
     * @return 持久化后的记录
     */
    @Transactional
    public ToolCallRecord createRecord(ToolCallRecord record) {
        if (record.getStatus() == null) {
            record.setStatus(ToolCallStatus.PENDING);
        }
        ToolCallRecord saved = repository.save(record);
        log.debug("创建工具调用记录: toolCallId={}, toolName={}, status={}",
                saved.getToolCallId(), saved.getToolName(), saved.getStatus());
        return saved;
    }

    /**
     * 更新工具调用记录（状态、输出、耗时等）
     *
     * @param record 已更新字段的记录
     * @return 更新后的记录
     */
    @Transactional
    public ToolCallRecord updateRecord(ToolCallRecord record) {
        ToolCallRecord updated = repository.save(record);
        log.debug("更新工具调用记录: toolCallId={}, status={}, durationMs={}",
                updated.getToolCallId(), updated.getStatus(), updated.getDurationMs());
        return updated;
    }

    /**
     * 更新记录状态
     */
    @Transactional
    public ToolCallRecord updateStatus(String toolCallId, ToolCallStatus status) {
        Optional<ToolCallRecord> opt = repository.findByToolCallId(toolCallId);
        if (opt.isPresent()) {
            ToolCallRecord record = opt.get();
            record.setStatus(status);
            return repository.save(record);
        }
        log.warn("未找到 toolCallId={} 的记录，无法更新状态", toolCallId);
        return null;
    }

    /**
     * 更新记录为成功状态（含输出和耗时）
     */
    @Transactional
    public ToolCallRecord markSuccess(String toolCallId, ToolResult result) {
        Optional<ToolCallRecord> opt = repository.findByToolCallId(toolCallId);
        if (opt.isPresent()) {
            ToolCallRecord record = opt.get();
            record.setStatus(ToolCallStatus.SUCCESS);
            record.setDurationMs(result.getDurationMs());
            record.setOutput(toJson(result.getData()));
            return repository.save(record);
        }
        log.warn("未找到 toolCallId={} 的记录，无法标记成功", toolCallId);
        return null;
    }

    /**
     * 更新记录为失败/超时/阻断状态
     */
    @Transactional
    public ToolCallRecord markFailed(String toolCallId, ToolCallStatus status, ToolResult result) {
        Optional<ToolCallRecord> opt = repository.findByToolCallId(toolCallId);
        if (opt.isPresent()) {
            ToolCallRecord record = opt.get();
            record.setStatus(status);
            record.setDurationMs(result.getDurationMs());
            record.setErrorMessage(result.getErrorMessage());
            record.setOutput(toJson(result.getData()));
            return repository.save(record);
        }
        log.warn("未找到 toolCallId={} 的记录，无法标记失败", toolCallId);
        return null;
    }

    /**
     * 根据工具调用 ID 查询
     */
    public Optional<ToolCallRecord> findByToolCallId(String toolCallId) {
        return repository.findByToolCallId(toolCallId);
    }

    /**
     * 查询某消息的所有工具调用记录
     */
    public List<ToolCallRecord> findByMessageId(Long messageId) {
        return repository.findByMessageIdOrderByCreatedAtAsc(messageId);
    }

    /**
     * 根据 auditId 查询
     */
    public List<ToolCallRecord> findByAuditId(String auditId) {
        return repository.findByAuditId(auditId);
    }

    // ==================== 内部工具方法 ====================

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("序列化工具输出为 JSON 失败: {}", e.getMessage());
            return obj.toString();
        }
    }
}
