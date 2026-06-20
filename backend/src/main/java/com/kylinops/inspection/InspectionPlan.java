package com.kylinops.inspection;

import com.kylinops.common.BaseEntity;
import com.kylinops.inspection.model.InspectionNotificationPolicy;
import com.kylinops.inspection.model.InspectionScheduleType;
import com.kylinops.inspection.model.InspectionTemplateType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 巡检计划持久化实体(P1-02 Plan 02 — Task 2)。
 *
 * <p>对应表 {@code inspection_plans},关键约束:</p>
 * <ul>
 *   <li>{@code plan_id} 业务主键(VARCHAR(36) UNIQUE),管理员可见 ID</li>
 *   <li>{@code name} 唯一(uk_inspection_plan_name) — 计划名去重</li>
 *   <li>{@code template_params_json} / {@code thresholds_json} / {@code schedule_config_json}
 *       均为 TEXT/CLOB 字段,Service 层用 Jackson 序列化/反序列化</li>
 *   <li>{@code version} {@code @Version} 乐观锁,管理 API 并发更新冲突返回 HTTP 409</li>
 *   <li>{@code next_run_at} 调度器拉取到期计划的关键索引(idx_inspection_plan_due)</li>
 *   <li>{@code enabled} 默认 false(新建计划默认停用,设计 §5.1)</li>
 * </ul>
 *
 * <p>时间字段全部为 UTC {@link LocalDateTime}(数据库 TIMESTAMP NOT NULL)。
 * 调度配置存为结构化 JSON,不做 cron 字符串解析。</p>
 */
@Entity
@Table(name = "inspection_plans",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_inspection_plan_name", columnNames = "name")
        },
        indexes = {
                @Index(name = "idx_inspection_plan_due", columnList = "next_run_at")
        })
@Getter
@Setter
@NoArgsConstructor
public class InspectionPlan extends BaseEntity {

    /** 业务主键(UUID),管理员可见。 */
    @Column(name = "plan_id", nullable = false, length = 36, unique = true, updatable = false)
    private String planId;

    /** 计划名称,管理员侧去重键。 */
    @Column(name = "name", nullable = false, length = 128)
    private String name;

    /** 计划说明(可空)。 */
    @Column(name = "description", length = 512)
    private String description;

    /** 模板类型:HEALTH / DISK / SERVICE(枚举固定)。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "template_type", nullable = false, length = 16)
    private InspectionTemplateType templateType;

    /** 模板参数 JSON 字符串(Jackson 序列化在 Service 层)。 */
    @Lob
    @Column(name = "template_params_json", columnDefinition = "TEXT")
    private String templateParamsJson;

    /** 阈值 JSON 字符串。 */
    @Lob
    @Column(name = "thresholds_json", columnDefinition = "TEXT")
    private String thresholdsJson;

    /** 调度周期:DAILY / WEEKLY / MONTHLY。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_type", nullable = false, length = 16)
    private InspectionScheduleType scheduleType;

    /** 调度配置 JSON 字符串({localTime, dayOfWeek, dayOfMonth})。 */
    @Lob
    @Column(name = "schedule_config_json", nullable = false, columnDefinition = "TEXT")
    private String scheduleConfigJson;

    /** IANA 时区 ID(例如 Asia/Shanghai)。 */
    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    /** 通知策略:ALWAYS / ON_ABNORMAL / NEVER。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "notification_policy", nullable = false, length = 16)
    private InspectionNotificationPolicy notificationPolicy;

    /** 是否启用,新建默认 false。 */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** 下次执行时间(UTC),调度器查询核心索引字段。 */
    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    /** 上次执行时间(UTC,nullable,从未执行过则为空)。 */
    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    /** JPA 乐观锁,管理 API 并发更新冲突返回 HTTP 409。 */
    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
