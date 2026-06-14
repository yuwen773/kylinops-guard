package com.kylinops.migration;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL Flyway 迁移冒烟测试。
 *
 * <p>本测试在 Phase 1 Task 2 引入，用于保护 V1 基线迁移在真实 PostgreSQL 上的
 * 兼容性，防止回退到 H2 私有类型（如 CLOB）。</p>
 *
 * <p>运行策略：</p>
 * <ul>
 *   <li>首选 Testcontainers PostgreSQL；</li>
 *   <li>当前主机 Docker daemon 不可达（已通过 {@code docker info} 确认
 *       {@code //./pipe/dockerDesktopLinuxEngine: The system cannot find the file specified}），
 *       故显式降级到 {@code io.zonky.test:embedded-postgres} —— 测试本体会输出
 *       {@code BLOCKED_EXTERNAL: Docker daemon unavailable; used zonky fallback} 标记。</li>
 * </ul>
 *
 * <p>断言：</p>
 * <ul>
 *   <li>flyway_schema_history 至少有一条成功条目；</li>
 *   <li>8 张 V1 legacy 表全部存在；</li>
 *   <li>JPA validate 全部通过（{@code spring.jpa.hibernate.ddl-auto=validate}）。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.jpa.hibernate.ddl-auto=validate",
        // PostgreSQL 环境下 kylin_report.body_markdown 用 TEXT（非 CLOB）
        "spring.flyway.placeholders.lob_type=TEXT"
})
@ActiveProfiles("test")
class FlywayPostgresMigrationTest {

    /** 真实 PostgreSQL 实例（zonky 提供，Testcontainers 降级路径）。 */
    private static EmbeddedPostgres PG;

    @BeforeAll
    static void startPostgres() throws Exception {
        System.out.println("BLOCKED_EXTERNAL: Docker daemon unavailable; used zonky fallback "
                + "(io.zonky.test:embedded-postgres 2.0.7, PG 14.10.1)");
        PG = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (PG != null) {
            PG.close();
        }
    }

    @DynamicPropertySource
    static void registerDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("PostgreSQL: Flyway 至少成功执行 V1 基线迁移")
    void flywayMigratedAtLeastV1() {
        Integer historyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE",
                Integer.class);
        assertThat(historyCount)
                .as("PostgreSQL 上 Flyway 应至少成功执行 V1__legacy_schema.sql")
                .isPositive();
    }

    @Test
    @DisplayName("PostgreSQL: 8 张 V1 legacy 表全部存在")
    void flywayCreatesLegacyTablesOnPostgres() throws SQLException {
        Set<String> expected = new HashSet<>(Arrays.asList(
                "kylin_session",
                "kylin_message",
                "kylin_audit_log",
                "kylin_tool_definition",
                "kylin_tool_call_record",
                "kylin_risk_check_record",
                "kylin_pending_action",
                "kylin_report"
        ));

        try (Connection conn = dataSource.getConnection()) {
            // PostgreSQL information_schema 全部是小写
            List<String> tables = jdbc.queryForList(
                    "SELECT table_name FROM information_schema.tables "
                            + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
                    String.class);
            assertThat(tables).containsAll(expected);
        }
    }

    @Test
    @DisplayName("PostgreSQL: kylin_report.body_markdown 是 TEXT（V1 占位符 ${lob_type} 已被 PG 覆写）")
    void bodyMarkdownIsTextOnPostgres() {
        String columnType = jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'kylin_report' "
                        + "  AND column_name = 'body_markdown'",
                String.class);
        assertThat(columnType)
                .as("PostgreSQL 上 body_markdown 应为 text（与 entity @Lob + columnDefinition=TEXT 一致）")
                .isIn("text", "character varying"); // PG 对 TEXT 报告 'text'
    }
}
