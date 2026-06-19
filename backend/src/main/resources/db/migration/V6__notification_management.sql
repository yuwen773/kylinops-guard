-- ============================================================
-- V6: Notification Center Management (Phase 1 / P1-01 Plan 01 Task 1)
-- ============================================================
-- 目标：在 V5 notification_records 之上引入"通知配置管理"所需的判别列 + 两张配置表。
--
-- 触发原因（P1-01 设计文档 §4.2 + Plan 01）：
--   * 通知中心管理页允许对通道做"测试连接"，会产生一条 TEST 类型记录。
--   * TEST 记录没有对应审计事件，audit_id 必须可空。
--   * notification_settings 单例表保存"全局启用 + dry_run + version"快照。
--   * notification_channels 表保存运行时通道实例及其配置。
--
-- 严格遵守 CLAUDE.md / phase1-runtime-database-plan：
--   * 仅增量：不删列、不改名、不破坏既有数据
--   * SQL 子集：bigint / varchar / text / timestamp / boolean / integer / smallint
--     与 V1-V5 完全一致（保持 H2 PostgreSQL 模式 + 真实 PostgreSQL 双向兼容）
--   * 不引用其它表的外键（避免 V1-V5 schema 演进时牵连）
--
-- 安全红线（来自 P1-01 设计 §4.6 + §6）：
--   * notification_channels.encrypted_secret 仅存加密后密文，禁止存明文
--   * channel 列表查询使用 idx_notification_channels_active (deleted_at, channel_id)
--     实现"软删除过滤 + 按 ID 排序"的一体索引，避免触发器
-- ============================================================

-- notification_records 增加 event_type 判别列 + 将 audit_id 改为可空
ALTER TABLE notification_records ADD COLUMN event_type VARCHAR(40);
ALTER TABLE notification_records ALTER COLUMN audit_id DROP NOT NULL;

-- 按 event_type + created_at DESC 的复合索引，用于"按类型筛选最新 N 条"
CREATE INDEX idx_notification_event_created
    ON notification_records (event_type, created_at DESC);

-- 全局通知设置（单例表：CHECK id=1）
CREATE TABLE notification_settings (
    id          SMALLINT     PRIMARY KEY,
    enabled     BOOLEAN      NOT NULL,
    dry_run     BOOLEAN      NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    CONSTRAINT ck_notification_settings_singleton CHECK (id = 1)
);

-- 通知通道运行时配置表
CREATE TABLE notification_channels (
    channel_id         VARCHAR(100)  PRIMARY KEY,
    channel_type       VARCHAR(20)   NOT NULL,
    enabled            BOOLEAN       NOT NULL,
    url                VARCHAR(2048) NOT NULL,
    encrypted_secret   TEXT,
    timeout_ms         INTEGER       NOT NULL,
    deleted_at         TIMESTAMP,
    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         TIMESTAMP     NOT NULL,
    updated_at         TIMESTAMP     NOT NULL
);

-- "活跃通道 + 按 channel_id 排序"的一体索引（替代 trigger，软删除由 deleted_at IS NULL 过滤）
CREATE INDEX idx_notification_channels_active
    ON notification_channels (deleted_at, channel_id);