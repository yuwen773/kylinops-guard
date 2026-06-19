package com.kylinops.notification.config;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 通知设置 Repository — 单例表(id 固定为 1)。
 */
@Repository
public interface NotificationSettingsRepository extends JpaRepository<NotificationSettingsEntity, Short> {
}