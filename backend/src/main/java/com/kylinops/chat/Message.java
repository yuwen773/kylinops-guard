package com.kylinops.chat;

import com.kylinops.common.BaseEntity;
import com.kylinops.common.enums.IntentType;
import com.kylinops.tool.ToolCallRecord;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息实体
 * <p>
 * 表示一次对话中的单条消息，可以是用户输入或 Agent 回复。
 * 一个消息可以触发多个工具调用。
 * </p>
 */
@Entity
@Table(name = "kylin_message")
@Getter
@Setter
public class Message extends BaseEntity {

    /** 消息唯一标识（UUID，对外暴露） */
    @Column(nullable = false, unique = true, length = 36)
    private String messageId;

    /** 所属会话 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private Session session;

    /** 消息角色：user / assistant / system */
    @Column(nullable = false, length = 16)
    private String role;

    /** 消息内容 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 识别到的意图类型 */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private IntentType intentType;

    /** 关联的审计日志 ID（贯穿全链路） */
    @Column(length = 36)
    private String auditId;

    /** 关联的工具调用记录 */
    @OneToMany(mappedBy = "message", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    private List<ToolCallRecord> toolCalls = new ArrayList<>();

    @PrePersist
    @Override
    protected void onCreate() {
        super.onCreate();
        if (this.messageId == null || this.messageId.isBlank()) {
            this.messageId = java.util.UUID.randomUUID().toString();
        }
    }
}
