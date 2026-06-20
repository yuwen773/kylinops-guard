package com.kylinops.migration;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 旧 v0.1 H2 schema 的 fingerprint 检测工具。
 *
 * <p>职责：把当前数据库的 {@code KYLIN_*} 表集合与 V1__legacy_schema.sql 冻结
 * 的 8 张表名集合做严格比对。本类是 Phase 1 Task 2（legacy H2 升级）的
 * 共享 helper，被 {@link LegacyH2UpgradeTest} 与
 * {@link PlaceholderResolutionTest} 调用，避免在两处重复硬编码表名清单。</p>
 *
 * <p>设计取舍：</p>
 * <ul>
 *   <li>用 {@code INFORMATION_SCHEMA.TABLES} 而不是 H2 私有系统表 —— 保证
 *       在 H2 / PostgreSQL 上都能跑（一旦 P1-T3 引入 production profile，
 *       同一 helper 可在 PG 上复用）。</li>
 *   <li>用 {@code UPPER(TABLE_NAME)} 做大小写不敏感比对，兼容 H2 PG 模式
 *       的「未加引号 → 大写折叠」行为。</li>
 *   <li>不依赖具体 schema 内容（列、索引），因为 V1 冻结 8 张表名已经能
 *       满足「表是否被删/被改」的最强保护 —— 列名变更属于后续
 *       {@code kylin_*_columns} 子集测试范畴。</li>
 * </ul>
 */
final class SchemaFingerprint {

    /**
     * V1__legacy_schema.sql 冻结的 8 张 legacy 表名（H2 PG 模式大写存储）。
     */
    static final Set<String> V1_TABLES;

    /**
     * V7__scheduled_inspection.sql 新增的巡检表名（H2 PG 模式大写存储）。
     */
    static final Set<String> INSPECTION_TABLES;

    /**
     * V7__scheduled_inspection.sql 创建的索引名（H2 PG 模式大写存储）。
     */
    static final Set<String> INSPECTION_INDEXES;

    static {
        Set<String> v1 = new TreeSet<>();
        v1.add("KYLIN_SESSION");
        v1.add("KYLIN_MESSAGE");
        v1.add("KYLIN_AUDIT_LOG");
        v1.add("KYLIN_TOOL_DEFINITION");
        v1.add("KYLIN_TOOL_CALL_RECORD");
        v1.add("KYLIN_RISK_CHECK_RECORD");
        v1.add("KYLIN_PENDING_ACTION");
        v1.add("KYLIN_REPORT");
        V1_TABLES = Collections.unmodifiableSet(v1);

        Set<String> inspTables = new TreeSet<>();
        inspTables.add("INSPECTION_PLANS");
        inspTables.add("INSPECTION_EXECUTIONS");
        INSPECTION_TABLES = Collections.unmodifiableSet(inspTables);

        Set<String> inspIdx = new TreeSet<>();
        inspIdx.add("UK_INSPECTION_PLAN_NAME");
        inspIdx.add("IDX_INSPECTION_PLAN_DUE");
        inspIdx.add("IDX_INSPECTION_EXECUTION_PLAN_STARTED");
        inspIdx.add("IDX_INSPECTION_EXECUTION_STATUS");
        INSPECTION_INDEXES = Collections.unmodifiableSet(inspIdx);
    }

    private SchemaFingerprint() {
        // utility class
    }

    /**
     * 读取当前数据库中所有 {@code KYLIN_*} 表名（H2 PG 模式大写）。
     * 排除 V2/V3 增量迁移新增的表：
     * <ul>
     *   <li>V2: {@code KYLIN_EXECUTION_ATTEMPT} / {@code KYLIN_EXECUTION_OUTCOME}
     *       —— legacy fixture 升级后由 V2 创建，不应参与「与 V1 fingerprint 比对」</li>
     *   <li>V3: {@code KYLIN_LLM_CALL_RECORD} —— P3-T5 LLM 调用审计，
     *       legacy fixture 不包含，故也不参与 V1 比对</li>
     * </ul>
     */
    static Set<String> readKylinTables(JdbcTemplate jdbc) {
        List<String> names = jdbc.queryForList(
                "SELECT \"TABLE_NAME\" FROM \"INFORMATION_SCHEMA\".\"TABLES\" "
                        + "WHERE UPPER(\"TABLE_NAME\") LIKE 'KYLIN_%'",
                String.class);
        Set<String> upper = new HashSet<>();
        for (String n : names) {
            upper.add(n == null ? "" : n.toUpperCase());
        }
        upper.remove("KYLIN_EXECUTION_ATTEMPT");
        upper.remove("KYLIN_EXECUTION_OUTCOME");
        upper.remove("KYLIN_LLM_CALL_RECORD");
        return upper;
    }

    /**
     * 与 V1 fingerprint 严格比对：返回缺失/多余表名，方便负向测试定位。
     */
    static MismatchDiff diffAgainstV1(JdbcTemplate jdbc) {
        Set<String> actual = readKylinTables(jdbc);
        Set<String> missing = new TreeSet<>(V1_TABLES);
        missing.removeAll(actual);
        Set<String> extra = new TreeSet<>(actual);
        extra.removeAll(V1_TABLES);
        return new MismatchDiff(missing, extra);
    }

    /**
     * 直接断言当前 schema 与 V1 fingerprint 一致；不一致时抛 AssertionError。
     * 该方法由 surefire 真实调用，不是注释占位。
     */
    static void assertMatchesV1(JdbcTemplate jdbc) {
        MismatchDiff diff = diffAgainstV1(jdbc);
        if (!diff.isEmpty()) {
            throw new AssertionError(
                    "legacy schema fingerprint 与 V1__legacy_schema.sql 不一致 — "
                            + "missing=" + diff.missing() + ", extra=" + diff.extra()
                            + "。V1 任何表名/列变更必须同步更新本 helper 与 V1 SQL 注释。");
        }
    }

    /** 简单 value object：missing / extra 集合。 */
    record MismatchDiff(Set<String> missing, Set<String> extra) {
        boolean isEmpty() {
            return missing.isEmpty() && extra.isEmpty();
        }
    }

    /** 显式列出 V1 表名（便于负向测试的诊断信息）。 */
    static List<String> v1TableNames() {
        return Arrays.asList(V1_TABLES.toArray(new String[0]));
    }

    /**
     * 断言 V7 巡检表已创建（{@code inspection_plans} / {@code inspection_executions}）。
     * 使用 {@code UPPER(table_name)} 兼容 H2 大写折叠与 PG 小写。
     */
    static void assertInspectionTablesPresent(JdbcTemplate jdbc) {
        List<String> rows = jdbc.query(
                "SELECT UPPER(table_name) FROM information_schema.tables "
                        + "WHERE UPPER(table_name) IN ('INSPECTION_PLANS','INSPECTION_EXECUTIONS')",
                (rs, rowNum) -> rs.getString(1));
        Set<String> actual = new HashSet<>(rows);
        Set<String> missing = new TreeSet<>(INSPECTION_TABLES);
        missing.removeAll(actual);
        assertThat(missing)
                .as("V7__scheduled_inspection.sql 必须创建 inspection_plans / inspection_executions 表;"
                        + " missing=%s, actual=%s", missing, actual)
                .isEmpty();
    }

    /**
     * 断言 V7 巡检索引全部创建。
     * <p>索引查询跨 H2 / PG 不可移植:</p>
     * <ul>
     *   <li>H2 PG 模式:{@code information_schema.indexes} 可用</li>
     *   <li>PostgreSQL 14+ 没有 {@code information_schema.indexes},需要查 {@code pg_indexes}</li>
     * </ul>
     * <p>本方法根据 JDBC {@link DatabaseMetaData#getDatabaseProductName()} 派发查询。</p>
     */
    static void assertInspectionIndexesPresent(JdbcTemplate jdbc) {
        Set<String> actual;
        try {
            actual = readIndexNames(jdbc);
        } catch (DataAccessException e) {
            throw new AssertionError("无法读取巡检索引列表 —— SchemaFingerprint 需要适配当前 DB 类型。"
                    + " 当前 SQL 错误: " + e.getMessage(), e);
        }
        Set<String> missing = new TreeSet<>(INSPECTION_INDEXES);
        missing.removeAll(actual);
        assertThat(missing)
                .as("V7__scheduled_inspection.sql 必须创建 4 个索引;missing=%s, actual=%s", missing, actual)
                .isEmpty();
    }

    private static Set<String> readIndexNames(JdbcTemplate jdbc) {
        String product = detectDatabaseProduct(jdbc);
        List<String> rows;
        if (product != null && product.toLowerCase().contains("postgres")) {
            // PostgreSQL:pg_indexes(schemaname, tablename, indexname)
            rows = jdbc.query(
                    "SELECT UPPER(indexname) FROM pg_indexes "
                            + "WHERE schemaname = 'public' "
                            + "  AND UPPER(indexname) IN "
                            + "('UK_INSPECTION_PLAN_NAME','IDX_INSPECTION_PLAN_DUE',"
                            + "'IDX_INSPECTION_EXECUTION_PLAN_STARTED','IDX_INSPECTION_EXECUTION_STATUS')",
                    (rs, rowNum) -> rs.getString(1));
        } else {
            // H2 PG 模式:information_schema.indexes 可用
            rows = jdbc.query(
                    "SELECT UPPER(index_name) FROM information_schema.indexes "
                            + "WHERE UPPER(index_name) IN "
                            + "('UK_INSPECTION_PLAN_NAME','IDX_INSPECTION_PLAN_DUE',"
                            + "'IDX_INSPECTION_EXECUTION_PLAN_STARTED','IDX_INSPECTION_EXECUTION_STATUS')",
                    (rs, rowNum) -> rs.getString(1));
        }
        return new HashSet<>(rows);
    }

    private static String detectDatabaseProduct(JdbcTemplate jdbc) {
        try (Connection conn = jdbc.getDataSource().getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            return md.getDatabaseProductName();
        } catch (SQLException e) {
            throw new IllegalStateException("无法获取数据库产品名", e);
        }
    }

    /**
     * 断言 {@code inspection_executions.plan_id} 列存在但不存在指向
     * {@code inspection_plans} 的数据库外键 —— 设计要求 plan_id 是普通 String 字段,
     * 删除计划时不能级联删除执行历史。
     */
    static void assertExecutionPlanIdHasNoForeignKeyToPlan(JdbcTemplate jdbc) {
        Long planIdColCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE UPPER(table_name) = 'INSPECTION_EXECUTIONS' "
                        + "  AND UPPER(column_name) = 'PLAN_ID'",
                Long.class);
        assertThat(planIdColCount)
                .as("inspection_executions.plan_id 列必须存在")
                .isEqualTo(1L);

        Long fkCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE UPPER(table_name) = 'INSPECTION_EXECUTIONS' "
                        + "  AND UPPER(constraint_type) = 'FOREIGN KEY'",
                Long.class);
        assertThat(fkCount)
                .as("inspection_executions 必须没有任何 FOREIGN KEY 约束(plan_id 是普通 String 字段,"
                        + " 删除计划不级联)")
                .isEqualTo(0L);
    }

    /**
     * 断言 {@code kylin_audit_log} 含 nullable {@code trigger_type VARCHAR(32)}
     * 与 {@code operator VARCHAR(128)} 两列 —— 巡检 Task 4 写入审计来源用。
     */
    static void assertAuditLogsTriggerColumnsPresent(JdbcTemplate jdbc) {
        for (String colName : new String[]{"TRIGGER_TYPE", "OPERATOR"}) {
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE UPPER(table_name) = 'KYLIN_AUDIT_LOG' "
                            + "  AND UPPER(column_name) = ?",
                    Long.class, colName);
            assertThat(count)
                    .as("kylin_audit_log.%s 列必须存在(V7 增量)", colName.toLowerCase())
                    .isEqualTo(1L);
        }

        // 两列必须 nullable (is_nullable='YES'),允许旧 v0.1 行不写
        for (String colName : new String[]{"TRIGGER_TYPE", "OPERATOR"}) {
            String isNullable = jdbc.queryForObject(
                    "SELECT is_nullable FROM information_schema.columns "
                            + "WHERE UPPER(table_name) = 'KYLIN_AUDIT_LOG' "
                            + "  AND UPPER(column_name) = ?",
                    String.class, colName);
            assertThat(isNullable)
                    .as("kylin_audit_log.%s 必须 nullable(旧 v0.1 行不写)", colName.toLowerCase())
                    .isEqualTo("YES");
        }
    }
}
