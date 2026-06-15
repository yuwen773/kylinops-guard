package com.kylinops.migration;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

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
}
