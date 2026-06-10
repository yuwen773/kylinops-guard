package com.kylinops.chat;

import com.kylinops.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话实体
 * <p>
 * 表示用户与 Agent 之间的一次对话会话。
 * 一个会话包含多条消息。
 * </p>
 */
@Entity
@Table(name = "kylin_session")
@Getter
@Setter
public class Session extends BaseEntity {

    /** 会话唯一标识（UUID，对外暴露） */
    @Column(nullable = false, unique = true, length = 36)
    private String sessionId;

    /** 会话标题 */
    @Column(length = 256)
    private String title;

    /** 会话状态：ACTIVE / CLOSED */
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    /** 关联的消息列表 */
    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    private List<Message> messages = new ArrayList<>();

    @PrePersist
    @Override
    protected void onCreate() {
        super.onCreate();
        if (this.sessionId == null || this.sessionId.isBlank()) {
            this.sessionId = java.util.UUID.randomUUID().toString();
        }
    }
}
