package com.kylinops.inspection;

import com.kylinops.common.BaseEntity;
import com.kylinops.inspection.model.InspectionExecutionStatus;
import com.kylinops.inspection.model.InspectionTriggerType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 巡检单次执行记录(P1-02 Plan 02 — Task 2)。
 *
 * <p>对应表 {@code inspection_executions},关键约束:</p>
 * <ul>
 *   <li>{@code execution_id} 业务主键(UUID),前端按此轮询状态</li>
 *   <li>{@code plan_id} 普通 String 字段(无 {@code @ManyToOne},无外键)
 *       —— 删除 plan 不级联 execution / report / audit 历史(设计 §5.3 + §5.4)</li>
 *   <li>{@code plan_snapshot_json} 计划快照,执行时固化用于历史回放</li>
 *   <li>{@code status} 终态机:RUNNING / SUCCESS / PARTIAL_SUCCESS / FAILED / SKIPPED</li>
 *   <li>{@code trigger_type} 与 {@code operator} 同时作为巡检来源标识(Task 4 写入审计)</li>
 *   <li>{@code audit_id} / {@code report_id} 闭环关联,nullable(报告生成失败时 report_id 为 null)</li>
 * </ul>
 */
@Entity
@Table(name = "inspection_executions",
        indexes = {
                @Index(name = "idx_inspection_execution_plan_started",
                        columnList = "plan_id, started_at DESC"),
                @Index(name = "idx_inspection_execution_status", columnList = "status")
        })
@Getter
@Setter
@NoArgsConstructor
public class InspectionExecution extends BaseEntity {

    /** 业务主键(UUID),管理员与前端可见。 */
    @Column(name = "execution_id", nullable = false, length = 36, unique = true, updatable = false)
    private String executionId;

    /** 原计划 ID —— 普通 String 字段,无外键(设计 §5.3)。 */
    @Column(name = "plan_id", nullable = false, length = 36, updatable = false)
    private String planId;

    /** 计划快照 JSON,执行时固化用于历史回放。 */
    @Lob
    @Column(name = "plan_snapshot_json", columnDefinition = "TEXT")
    private String planSnapshotJson;

    /** 执行状态:RUNNING / SUCCESS / PARTIAL_SUCCESS / FAILED / SKIPPED。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InspectionExecutionStatus status;

    /** 触发来源:SCHEDULED / MANUAL。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 32)
    private InspectionTriggerType triggerType;

    /** 操作主体:SYSTEM_SCHEDULER 或当前管理员用户名。 */
    @Column(name = "operator", nullable = false, length = 128)
    private String operator;

    /** 实际开始时间(UTC)。 */
    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    /** 实际结束时间(UTC,nullable — RUNNING 时为空)。 */
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** 摘要(可空,脱敏后写入)。 */
    @Column(name = "summary", length = 1024)
    private String summary;

    /** 关联审计 ID(报告生成失败 / 审计创建失败时为 null,设计 §9)。 */
    @Column(name = "audit_id", length = 36)
    private String auditId;

    /** 关联报告 ID(可空)。 */
    @Column(name = "report_id", length = 36)
    private String reportId;

    /** 被巡检系统是否异常(abnormal=true 触发 ON_ABNORMAL 通知)。 */
    @Column(name = "abnormal", nullable = false)
    private boolean abnormal;
}
