package com.kylinops.tool;

import com.kylinops.common.BaseEntity;
import com.kylinops.chat.Message;
import com.kylinops.common.enums.ToolCallStatus;
import com.kylinops.security.RiskCheckRecord;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * 工具调用记录实体
 * <p>
 * 记录每次 OpsTool 调用的完整生命周期：
 * 入参、出参、状态、耗时、关联的风险校验和审计日志。
 * </p>
 */
@Entity
@Table(name = "kylin_tool_call_record")
@Getter
@Setter
public class ToolCallRecord extends BaseEntity {

    /** 工具调用唯一标识（UUID，对外暴露） */
    @Column(nullable = false, unique = true, length = 36)
    private String toolCallId;

    /** 所属消息（可为空，支持独立于 Chat 流程的工具调用） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = true)
    private Message message;

    /** 工具名称 */
    @Column(nullable = false, length = 64)
    private String toolName;

    /** 调用输入参数（JSON） */
    @Column(columnDefinition = "TEXT")
    private String input;

    /** 调用输出结果（JSON） */
    @Column(columnDefinition = "TEXT")
    private String output;

    /** 调用状态 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ToolCallStatus status;

    /** 执行耗时（毫秒） */
    private Long durationMs;

    /** 错误信息 */
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /** 关联的审计日志 ID（贯穿全链路） */
    @Column(length = 36)
    private String auditId;

    /** 关联的风险校验记录 */
    @OneToOne(mappedBy = "toolCallRecord", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private RiskCheckRecord riskCheckRecord;

    @PrePersist
    @Override
    protected void onCreate() {
        super.onCreate();
        if (this.toolCallId == null || this.toolCallId.isBlank()) {
            this.toolCallId = java.util.UUID.randomUUID().toString();
        }
    }
}
