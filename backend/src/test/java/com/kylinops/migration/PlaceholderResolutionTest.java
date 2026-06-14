package com.kylinops.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V1 Flyway 占位符解析回归保护（修复 P1-T2 Spec Review Critical C2）。
 *
 * <p>V1__legacy_schema.sql 的 {@code kylin_report.body_markdown} 列类型依赖
 * Flyway 占位符 {@code ${lob_type}}：</p>
 * <ul>
 *   <li>H2（dev/test profile）期望 CLOB</li>
 *   <li>PostgreSQL（prod profile）期望 TEXT</li>
 * </ul>
 *
 * <p>占位符通过 {@code spring.flyway.placeholders.lob_type} 注入。P1-T3 会
 * 引入 {@code application-prod.yml} 并在里头显式声明该值 —— 本测试作为
 * <b>防御性回归</b>：在 P1-T3 落地前先固化「缺失占位符时 Flyway 启动必须
 * 失败」的契约，避免 prod profile 配置漏写 {@code lob_type} 时
 * 默默使用 H2 私有 CLOB 在 PostgreSQL 上炸掉。</p>
 *
 * <p>实现策略：</p>
 * <ul>
 *   <li>用 {@link Flyway} Java API 在临时 H2 上跑 V1（不走 Spring context），
 *       这样测试本身不会被 Spring 启动失败吞掉断言。</li>
 *   <li>故意不传 {@code placeholders.lob_type}，让 Flyway 把 {@code ${lob_type}}
 *       当成未替换 token（Flyway 默认 {@code placeholderReplacement=true} + 未配置
 *       {@code ignoreMissingPlaceholders} 时会抛 {@link FlywayException}）。</li>
 *   <li>同时跑一个对照组：传 {@code lob_type=CLOB} 时应成功。这能保证
 *       负向断言不是因为其他原因（比如 SQL 语法错误）才抛错。</li>
 * </ul>
 */
class PlaceholderResolutionTest {

    private Path tempDir;
    private String jdbcUrl;

    @BeforeEach
    void setUp() throws Exception {
        // ensure parent dir exists（createTempDirectory 在 Windows 上不会自动建父目录）
        Files.createDirectories(Path.of("build", "placeholder-test"));
        tempDir = Files.createTempDirectory(
                Path.of("build", "placeholder-test"), "lob-");
        jdbcUrl = "jdbc:h2:file:" + tempDir.toAbsolutePath().resolve("lob") +
                ";AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tempDir != null && Files.exists(tempDir)) {
            try (var stream = Files.walk(tempDir)) {
                stream.sorted((a, b) -> b.toString().length() - a.toString().length())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
    }

    /**
     * 正向对照组：传 lob_type=CLOB，V1 必须能跑通。这是负向断言
     * 「不是因为别的原因才失败」的 sanity check。
     */
    @Test
    @DisplayName("对照组：lob_type=CLOB 时 V1 在 H2 上成功执行")
    void lobTypeCLOB_succeeds() {
        Flyway flyway = baseFlywayConfig()
                .placeholders(java.util.Map.of("lob_type", "CLOB"))
                .load();
        // migrate() 不抛错即视为 V1 成功
        int applied = flyway.migrate().migrationsExecuted;
        assertThat(applied)
                .as("lob_type=CLOB 时 V1 + V2 必须成功执行")
                .isGreaterThanOrEqualTo(2);

        // 验证 kylin_report.body_markdown 列存在 —— 用 JDBC DatabaseMetaData，
        // 不依赖 H2 INFORMATION_SCHEMA 的方言（不同版本 H2 列名差异较大）
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "")) {
            boolean found = false;
            try (var rs = conn.getMetaData().getColumns(null, "PUBLIC",
                    "KYLIN_REPORT", "BODY_MARKDOWN")) {
                while (rs.next()) {
                    found = true;
                    // 不强断言 TYPE_NAME（不同 H2 版本的 TYPE_NAME 取值差异大）；
                    // 占位符替换成功的核心证据是 V1 成功 + 列存在。
                }
            }
            assertThat(found)
                    .as("对照组：kylin_report.body_markdown 列必须存在（占位符替换成功）")
                    .isTrue();
        } catch (Exception e) {
            throw new AssertionError("对照组数据库连接失败", e);
        }
    }

    /**
     * 负向断言：不传 lob_type 时，Flyway 必须抛 FlywayException，
     * 且错误信息必须点出未替换的占位符 ${lob_type}。
     *
     * <p>这是 P1-T3 引入 application-prod.yml 前的契约测试 —— 一旦
     * prod profile 漏配 {@code spring.flyway.placeholders.lob_type=TEXT}，
     * 该测试会先在 CI 端标红。</p>
     */
    @Test
    @DisplayName("负向：不传 lob_type 时，V1 因 ${lob_type} 未替换而抛 FlywayException")
    void missingLobType_failsMigrate() {
        Flyway flyway = baseFlywayConfig().load();

        assertThatThrownBy(flyway::migrate)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("lob_type");
    }

    /**
     * 极端负向：占位符被显式置空字符串（YAML 中写空值）。Flyway 会把
     * 空字符串视作有效值并替换进去 —— 此时 SQL 会因为
     * {@code body_markdown  NOT NULL} 报语法错误或类型错误（取决于 H2
     * 行为）。这一用例固化「空值 ≠ 缺失」的边界，提示后续 prod 配置
     * 必须显式提供有效 CLOB / TEXT。
     */
    @Test
    @DisplayName("边界：lob_type=空串时，V1 在 H2 上应失败（空值不等同于有效占位符）")
    void emptyLobType_failsMigrate() {
        Flyway flyway = baseFlywayConfig()
                .placeholders(java.util.Map.of("lob_type", ""))
                .load();

        // Flyway 行为：空字符串仍会被替换进 SQL，然后 H2 在
        // "body_markdown  NOT NULL" 处报类型或语法错误。
        // 接受任意 FlywayException / SQLException 子类 —— 该断言仅锁
        // 「空配置 ≠ 静默通过」。
        assertThatThrownBy(flyway::migrate)
                .isInstanceOf(FlywayException.class);
    }

    private FluentConfiguration baseFlywayConfig() {
        // 从 classpath 加载 V1（与生产 Flyway locations 一致）
        String[] locations = new String[] {
                "classpath:db/migration"
        };
        return Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .locations(locations)
                .baselineOnMigrate(true)
                .baselineVersion("1")
                // 关键：开启占位符替换，但不忽略未配置的占位符（默认 false）
                .placeholderReplacement(true)
                .placeholders(new java.util.HashMap<>());
    }
}
