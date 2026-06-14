package com.kylinops.migration;

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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 旧 v0.1 H2 → V2 升级回归测试（Phase 1 Task 2）。
 *
 * <p>本测试保护两件事：</p>
 * <ol>
 *   <li><b>fingerprint 校验</b>：fingerprint 与冻结 V1 不一致时（人为篡改列名），
 *       {@link SchemaFingerprint#assertMatchesV1(JdbcTemplate)} 必须抛
 *       {@link AssertionError}（防止「字段悄悄消失」的回归）。</li>
 *   <li><b>baseline + V2 upgrade</b>：fixture 中的旧 audit / report 行
 *       必须完整保留，不能被 V2 增量迁移破坏。</li>
 * </ol>
 *
 * <p>流程：</p>
 * <ul>
 *   <li>建立独立 H2 file-mode 数据库（build/legacy-test/）</li>
 *   <li>通过 {@code RUNSCRIPT FROM} 加载 fixture SQL（含 8 张表 + 脱敏种子）</li>
 *   <li>用 {@link SchemaFingerprint#assertMatchesV1(JdbcTemplate)} 读取
 *       {@code INFORMATION_SCHEMA.TABLES} 表名集合作为 schema fingerprint，
 *       断言 8 张表均存在</li>
 *   <li>Spring 启动时 {@code spring.flyway.baseline-on-migrate=true} +
 *       {@code spring.flyway.baseline-version=1} 应仅产生 baseline 标记 + V2 执行记录</li>
 *   <li>断言旧 audit / report 行仍然存在</li>
 * </ul>
 *
 * <p>负向用例：{@link #detectsFingerprintMismatch()} 在独立的损坏 H2 中
 * 故意把 {@code KYLIN_REPORT} 改成 {@code KYLIN_REPORT_BAD}，再让同一
 * {@link SchemaFingerprint#assertMatchesV1(JdbcTemplate)} helper 跑一次，
 * 断言其抛出 {@link AssertionError}。这是 P1-T2 Spec Review Critical C1
 * 的修复点：之前的 {@code fingerprintMismatchShouldFailStartup} 是个 fake
 * test（抛 {@code UnsupportedOperationException} 然后断言被抛出），本
 * 用例用真实损坏 schema 跑同一检测函数。</p>
 */
@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.jpa.hibernate.ddl-auto=validate",
        // baseline-on-migrate + baseline-version=1：v0.1 fixture 视为版本 1，V2 起继续升级
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.baseline-version=1",
        // V1 占位符：H2 期望 CLOB
        "spring.flyway.placeholders.lob_type=CLOB"
})
@ActiveProfiles("test")
class LegacyH2UpgradeTest {

    private static Path LEGACY_DB_DIR;
    private static String LEGACY_DB_URL;
    private static final String FIXTURE_SQL = "legacy/kylinops-v0.1-data.sql";

    @BeforeAll
    static void seedLegacyDatabase() throws SQLException, IOException {
        // 1. 准备独立 H2 file-mode 目录（与默认 kylinops-test 内存库完全隔离）
        Files.createDirectories(Path.of("build", "legacy-test"));
        LEGACY_DB_DIR = Files.createTempDirectory(Path.of("build", "legacy-test"), "legacy-");
        Files.createDirectories(LEGACY_DB_DIR);
        // H2 file-mode URL：AUTO_SERVER=TRUE 让 Spring Boot 进程可并发连接
        LEGACY_DB_URL = "jdbc:h2:file:" + LEGACY_DB_DIR.toAbsolutePath().resolve("legacy") +
                ";AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";

        // 2. 通过 H2 RUNSCRIPT 加载 fixture（schema + 脱敏种子数据）
        //    将 classpath 资源写入临时文件，让 H2 RUNSCRIPT 通过绝对路径加载
        Path fixtureFile = Files.createTempFile("kylinops-v0.1-fixture-", ".sql");
        try (var in = LegacyH2UpgradeTest.class.getClassLoader().getResourceAsStream(FIXTURE_SQL)) {
            assert in != null;
            Files.write(fixtureFile, in.readAllBytes());
        }
        try (Connection conn = DriverManager.getConnection(LEGACY_DB_URL, "sa", "");
             Statement stmt = conn.createStatement()) {
            // H2 RUNSCRIPT 用单引号包裹字符串路径；Windows 反斜杠无需转义（Java String 持有）
            stmt.execute("RUNSCRIPT FROM '" + fixtureFile.toAbsolutePath() + "'");
        } finally {
            Files.deleteIfExists(fixtureFile);
        }
    }

    @AfterAll
    static void cleanupLegacyDatabase() throws IOException {
        if (LEGACY_DB_DIR != null && Files.exists(LEGACY_DB_DIR)) {
            // 递归删除临时目录
            try (var stream = Files.walk(LEGACY_DB_DIR)) {
                stream.sorted((a, b) -> b.toString().length() - a.toString().length())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
        }
    }

    @DynamicPropertySource
    static void registerDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> LEGACY_DB_URL);
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("baseline + V2 升级后，flyway_schema_history 含 baseline 标记 + V2")
    void flywayAppliedBaselineAndV2() {
        // v0.1 fixture 被视为 baseline version=1；V2 是首次执行的「真实迁移」
        // Flyway 自己用引号创建 flyway_schema_history（小写），
        // 而 fixture 表名被 H2 PG 模式折叠为大写。
        List<String> descriptions = jdbc.queryForList(
                "SELECT \"description\" FROM \"flyway_schema_history\" ORDER BY \"installed_rank\"",
                String.class);

        assertThat(descriptions)
                .as("应包含 baseline（<< Flyway Baseline >>）+ V2 execution audit schema")
                .anyMatch(d -> d != null && d.contains("Baseline"))
                .anyMatch(d -> d != null && d.contains("execution audit schema"));

        // V2 必须成功
        Integer v2Success = jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" "
                        + "WHERE \"version\" = '2' AND \"success\" = TRUE",
                Integer.class);
        assertThat(v2Success)
                .as("V2__execution_audit_schema.sql 必须成功执行")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("baseline + V2 升级后，8 张 legacy 表 + V2 新表全部存在")
    void upgradePreservesLegacyTablesAndAddsV2() {
        // H2 PG 模式下表名一律大写存储，information_schema 返回原始大小写
        // V1 部分：8 张 legacy 表通过共享 helper 校验（与负向测试同源）
        assertThat(SchemaFingerprint.readKylinTables(jdbc))
                .as("V1 8 张 legacy 表必须保留")
                .contains("KYLIN_SESSION", "KYLIN_MESSAGE", "KYLIN_AUDIT_LOG",
                        "KYLIN_TOOL_DEFINITION", "KYLIN_TOOL_CALL_RECORD",
                        "KYLIN_RISK_CHECK_RECORD", "KYLIN_PENDING_ACTION", "KYLIN_REPORT");

        // V2 部分：增量迁移新增的两张表必须存在（不走 helper，因为 helper
        // 故意排除了 V2 表以保护「V1 fingerprint 比对」的纯粹性）
        List<String> v2Tables = jdbc.queryForList(
                "SELECT \"TABLE_NAME\" FROM \"INFORMATION_SCHEMA\".\"TABLES\" "
                        + "WHERE UPPER(\"TABLE_NAME\") IN ('KYLIN_EXECUTION_ATTEMPT', 'KYLIN_EXECUTION_OUTCOME')",
                String.class);
        assertThat(v2Tables)
                .as("V2__execution_audit_schema.sql 必须新增 KYLIN_EXECUTION_ATTEMPT / KYLIN_EXECUTION_OUTCOME")
                .containsExactlyInAnyOrder("KYLIN_EXECUTION_ATTEMPT", "KYLIN_EXECUTION_OUTCOME");
    }

    @Test
    @DisplayName("baseline + V2 升级后，旧 v0.1 的 audit / report 行仍然保留")
    void upgradePreservesLegacyAuditAndReportRows() {
        // 1 条 fixture audit 必须保留
        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"KYLIN_AUDIT_LOG\" WHERE \"AUDIT_ID\" = 'aud-fixture-0001'",
                Integer.class);
        assertThat(auditCount)
                .as("旧 v0.1 fixture 中的 audit 行不应被升级抹除")
                .isEqualTo(1);

        // 1 条 fixture report 必须保留
        Integer reportCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"KYLIN_REPORT\" WHERE \"REPORT_ID\" = 'rpt-fixture-0001'",
                Integer.class);
        assertThat(reportCount)
                .as("旧 v0.1 fixture 中的 report 行不应被升级抹除")
                .isEqualTo(1);

        // V2 新增到 kylin_pending_action 的两列必须可空（旧数据无 auth 信息）
        String nullableCreatorPrincipal = jdbc.queryForObject(
                "SELECT \"IS_NULLABLE\" FROM \"INFORMATION_SCHEMA\".\"COLUMNS\" "
                        + "WHERE UPPER(\"TABLE_NAME\") = 'KYLIN_PENDING_ACTION' "
                        + "  AND UPPER(\"COLUMN_NAME\") = 'CREATOR_PRINCIPAL'",
                String.class);
        assertThat(nullableCreatorPrincipal).isEqualTo("YES");

        String nullableCreatorAuth = jdbc.queryForObject(
                "SELECT \"IS_NULLABLE\" FROM \"INFORMATION_SCHEMA\".\"COLUMNS\" "
                        + "WHERE UPPER(\"TABLE_NAME\") = 'KYLIN_PENDING_ACTION' "
                        + "  AND UPPER(\"COLUMN_NAME\") = 'CREATOR_AUTH_SESSION_ID'",
                String.class);
        assertThat(nullableCreatorAuth).isEqualTo("YES");
    }

    /**
     * Fingerprint 防御：通过共享 helper 校验当前 schema 与 V1 严格 1:1 对齐。
     *
     * <p>该 helper 后续会被 {@link #detectsFingerprintMismatch()} 在损坏
     * schema 上再次调用，形成「合法/非法」双向回归保护。</p>
     */
    @Test
    @DisplayName("legacy fixture 的 schema fingerprint 必须与 V1__legacy_schema.sql 一致")
    void legacySchemaFingerprintMatchesV1() {
        // 走 helper，确保与负向测试用同一份 V1 冻结表名清单
        SchemaFingerprint.assertMatchesV1(jdbc);
    }

    /**
     * Fingerprint 篡改防御（真实负向测试 — 修复 Spec Review C1）。
     *
     * <p>建立一个独立的损坏 H2：故意把 V1 中的 {@code KYLIN_REPORT} 改成
     * {@code KYLIN_REPORT_BAD}，再让
     * {@link SchemaFingerprint#assertMatchesV1(JdbcTemplate)} 检测一次。
     * helper 必须抛 {@link AssertionError}，并把缺失/多余表名一并报出。</p>
     *
     * <p>该用例替代了之前的 fake test（fingerprintMismatchShouldFailStartup
     * — 抛 UnsupportedOperationException 然后断言被抛出的死循环）。</p>
     */
    @Test
    @DisplayName("fingerprint 损坏（KYLIN_REPORT → KYLIN_REPORT_BAD）时，helper 必须抛 AssertionError")
    void detectsFingerprintMismatch() throws Exception {
        // 1. 临时 H2 库：与 Spring Boot 注入的 DataSource 完全隔离
        Path corruptDir = Files.createTempDirectory(
                Path.of("build", "legacy-test"), "corrupt-");
        String corruptUrl = "jdbc:h2:file:" + corruptDir.toAbsolutePath().resolve("corrupt") +
                ";AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        try {
            // 2. 在临时 H2 中建 7 张「正常」legacy 表 + 1 张「故意改名」表
            try (Connection conn = DriverManager.getConnection(corruptUrl, "sa", "");
                 Statement stmt = conn.createStatement()) {

                // 7 张与 V1 严格一致（直接拿 V1 SQL 中的表名，不偷懒）
                stmt.execute("CREATE TABLE kylin_session ("
                        + "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                        + "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,"
                        + "session_id VARCHAR(36) NOT NULL UNIQUE,"
                        + "title VARCHAR(256), status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE')");
                stmt.execute("CREATE TABLE kylin_message ("
                        + "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                        + "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,"
                        + "message_id VARCHAR(36) NOT NULL UNIQUE,"
                        + "session_id BIGINT NOT NULL,"
                        + "role VARCHAR(16) NOT NULL, content TEXT NOT NULL,"
                        + "intent_type VARCHAR(32), audit_id VARCHAR(36))");
                stmt.execute("CREATE TABLE kylin_audit_log ("
                        + "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                        + "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,"
                        + "audit_id VARCHAR(36) NOT NULL UNIQUE,"
                        + "session_id VARCHAR(36), user_input TEXT,"
                        + "intent_type VARCHAR(32), tool_name VARCHAR(64),"
                        + "risk_level VARCHAR(8), risk_decision VARCHAR(12),"
                        + "status VARCHAR(20) NOT NULL, message TEXT,"
                        + "duration_ms BIGINT, matched_rules TEXT, action_plan TEXT,"
                        + "confirmation_required BOOLEAN NOT NULL DEFAULT FALSE,"
                        + "confirmation_status VARCHAR(20), execution_result TEXT,"
                        + "final_answer TEXT, warning TEXT)");
                stmt.execute("CREATE TABLE kylin_tool_definition ("
                        + "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                        + "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,"
                        + "tool_name VARCHAR(64) NOT NULL UNIQUE,"
                        + "description VARCHAR(512) NOT NULL,"
                        + "input_schema TEXT, output_schema TEXT,"
                        + "risk_level VARCHAR(8) NOT NULL,"
                        + "permission_type VARCHAR(12) NOT NULL,"
                        + "tool_status VARCHAR(12) NOT NULL DEFAULT 'ENABLED',"
                        + "timeout_ms BIGINT NOT NULL DEFAULT 3000,"
                        + "audit_required BOOLEAN NOT NULL DEFAULT TRUE)");
                stmt.execute("CREATE TABLE kylin_tool_call_record ("
                        + "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                        + "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,"
                        + "tool_call_id VARCHAR(36) NOT NULL UNIQUE,"
                        + "message_id BIGINT, tool_name VARCHAR(64) NOT NULL,"
                        + "input TEXT, output TEXT, status VARCHAR(16) NOT NULL,"
                        + "duration_ms BIGINT, error_message TEXT, audit_id VARCHAR(36))");
                stmt.execute("CREATE TABLE kylin_risk_check_record ("
                        + "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                        + "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,"
                        + "risk_check_id VARCHAR(36) NOT NULL UNIQUE,"
                        + "target_type VARCHAR(20), target_content TEXT,"
                        + "tool_call_record_id BIGINT,"
                        + "risk_level VARCHAR(8) NOT NULL,"
                        + "risk_decision VARCHAR(12) NOT NULL,"
                        + "matched_rules TEXT, reason TEXT NOT NULL,"
                        + "safe_suggestion TEXT, audit_id VARCHAR(36),"
                        + "checked_at TIMESTAMP NOT NULL)");
                stmt.execute("CREATE TABLE kylin_pending_action ("
                        + "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                        + "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,"
                        + "action_id VARCHAR(36) NOT NULL UNIQUE,"
                        + "audit_id VARCHAR(64) NOT NULL, session_id VARCHAR(64),"
                        + "action_type VARCHAR(64) NOT NULL,"
                        + "tool_name VARCHAR(128), params_json TEXT,"
                        + "risk_level VARCHAR(8) NOT NULL,"
                        + "status VARCHAR(16) NOT NULL,"
                        + "expires_at TIMESTAMP NOT NULL, execution_result TEXT)");

                // 关键：第 8 张表 KYLIN_REPORT 故意改名 KYLIN_REPORT_BAD
                // 模拟「V1 改了表名 / 字段悄悄消失」的回归
                stmt.execute("CREATE TABLE kylin_report_bad ("
                        + "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                        + "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,"
                        + "report_id VARCHAR(36) NOT NULL UNIQUE,"
                        + "report_type VARCHAR(32) NOT NULL,"
                        + "title VARCHAR(256) NOT NULL,"
                        + "session_id VARCHAR(36), audit_id VARCHAR(36) NOT NULL,"
                        + "risk_level VARCHAR(8), body_markdown CLOB NOT NULL)");
            }

            // 3. 用 JdbcTemplate 套同一份损坏库，调用与正向用例共享的 helper
            org.springframework.jdbc.core.JdbcTemplate corruptJdbc =
                    new org.springframework.jdbc.core.JdbcTemplate(
                            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                    corruptUrl, "sa", ""));
            SchemaFingerprint.MismatchDiff diff = SchemaFingerprint.diffAgainstV1(corruptJdbc);

            // 4. 双向断言：缺失 KYLIN_REPORT + 多余 KYLIN_REPORT_BAD
            assertThat(diff.missing())
                    .as("损坏库应缺失 KYLIN_REPORT")
                    .containsExactly("KYLIN_REPORT");
            assertThat(diff.extra())
                    .as("损坏库应多出 KYLIN_REPORT_BAD")
                    .containsExactly("KYLIN_REPORT_BAD");

            // 5. 终极断言：assertMatchesV1 必须抛 AssertionError（不是空跑）
            assertThatThrownBy(() -> SchemaFingerprint.assertMatchesV1(corruptJdbc))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("KYLIN_REPORT")
                    .hasMessageContaining("KYLIN_REPORT_BAD");
        } finally {
            // 6. 清理临时 H2（递归删除）
            try (var stream = Files.walk(corruptDir)) {
                stream.sorted((a, b) -> b.toString().length() - a.toString().length())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
        }
    }
}
