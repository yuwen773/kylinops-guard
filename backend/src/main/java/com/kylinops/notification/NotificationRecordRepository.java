package com.kylinops.notification;

import org.springframework.data.domain.Pageable;
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

    /**
     * 按事件类型查最新 20 条记录，按 created_at 倒序。
     * 用于"测试连接"页与管理端"按事件类型过滤"展示。
     *
     * @param eventType 事件类型（必传；TEST 用于测试连接记录，auditId 必然为 NULL）
     * @return 倒序的最多 20 条记录
     */
    List<NotificationRecord> findTop20ByEventTypeOrderByCreatedAtDesc(
            NotificationEventType eventType);

    /**
     * 按事件类型查最新 N 条记录，按 created_at 倒序。用于"管理端测试连接"页 —
     * 可变 limit（接口侧 clamp 到 [1, 20]）。
     *
     * @param eventType 事件类型（必传；TEST 用于测试连接记录）
     * @param pageable  分页参数（仅 size/offset 有意义；按 created_at DESC 排序）
     * @return 倒序的 N 条记录
     */
    List<NotificationRecord> findByEventTypeOrderByCreatedAtDesc(
            NotificationEventType eventType, Pageable pageable);

    /**
     * 按 (channelId, eventType) 查最新一条记录，按 created_at 倒序。
     * 用于"管理端测试连接"页 — 同一通道最近一次测试结果。
     *
     * @param channelId 通道实例 ID
     * @param eventType 事件类型
     * @return 存在则返回对应记录，否则 Optional.empty()
     */
    Optional<NotificationRecord> findFirstByChannelIdAndEventTypeOrderByCreatedAtDesc(
            String channelId, NotificationEventType eventType);
}
