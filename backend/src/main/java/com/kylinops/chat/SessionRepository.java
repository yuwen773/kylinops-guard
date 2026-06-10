package com.kylinops.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 会话 Repository
 */
@Repository
public interface SessionRepository extends JpaRepository<Session, Long> {

    /** 根据 sessionId 查询会话 */
    Optional<Session> findBySessionId(String sessionId);

    /** 查询所有活跃会话，按更新时间降序排列 */
    java.util.List<Session> findByStatusOrderByUpdatedAtDesc(String status);
}
