package com.kylinops.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 通知记录 Repository（P1-01 Plan 01 — Task 2）。
 *
 * <p>主要用途：</p>
 * <ul>
 *   <li>dispatcher（C5）写 PENDING → SENT / FAILED 状态更新</li>
 *   <li>dispatcher 写入前用 {@link #findFirstByEventIdAndChannelId} 做幂等检查
 *       （防止同一事件对同一通道重复入队）</li>
 *   <li>AuditLogDetail 联表（C7）查询事件关联的通知</li>
 * </ul>
 */
@Repository
public interface NotificationRecordRepository extends JpaRepository<NotificationRecord, String> {

    /**
     * 按 (eventId, channelId) 查第一条记录（用于 dispatcher 幂等检查）。
     *
     * @param eventId   事件 ID
     * @param channelId 通道实例 ID（如 "webhook-prod"）
     * @return 存在则返回对应记录，否则 Optional.empty()
     */
    Optional<NotificationRecord> findFirstByEventIdAndChannelId(String eventId, String channelId);

    List<NotificationRecord> findByAuditIdOrderByCreatedAtDesc(String auditId);
}
