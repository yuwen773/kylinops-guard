package com.kylinops.notification.config;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 通知通道 Repository。
 *
 * <p>查询约定:</p>
 * <ul>
 *   <li>{@link #findAllByDeletedAtIsNullOrderByChannelIdAsc()} — 快照用,
 *       排除软删除且按 channel_id 升序</li>
 *   <li>{@link #existsByChannelId(String)} — ID 唯一性检查,包含软删除行
 *       (避免复用旧 ID)</li>
 *   <li>{@link #findByChannelId(String)} — 含软删除行的全量查询(供冲突检查)</li>
 * </ul>
 */
@Repository
public interface NotificationChannelRepository extends JpaRepository<NotificationChannelEntity, String> {

    /**
     * 查找全部未软删除的通道,按 channel_id 升序。
     */
    List<NotificationChannelEntity> findAllByDeletedAtIsNullOrderByChannelIdAsc();

    /**
     * 检查 channel_id 是否已被占用(包含软删除行)。
     * 一旦 ID 被使用(无论是否删除)都视为已占用,防止重用造成审计混乱。
     */
    boolean existsByChannelId(String channelId);

    /**
     * 按 channel_id 查询(可能返回已软删除的行 — 用于冲突消息)。
     */
    Optional<NotificationChannelEntity> findByChannelId(String channelId);
}