package com.kylinops.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 消息 Repository
 */
@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    /** 根据 messageId 查询消息 */
    Optional<Message> findByMessageId(String messageId);

    /** 查询某会话的所有消息，按创建时间升序排列 */
    List<Message> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    /** 查询某会话的所有消息（通过 sessionId 字符串），按创建时间升序排列 */
    List<Message> findBySessionSessionIdOrderByCreatedAtAsc(String sessionId);

    /** 根据 auditId 查询消息 */
    Optional<Message> findByAuditId(String auditId);

    /** 查询某角色的所有消息 */
    List<Message> findByRole(String role);
}
