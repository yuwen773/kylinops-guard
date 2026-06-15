package com.kylinops.migration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * SchemaFingerprintMain — 独立可执行入口（Phase 4 P4-T2 引入）。
 *
 * <p>职责：将 legacy H2 schema fingerprint 校验逻辑暴露为可独立运行的 Java
 * 程序，供 {@code migrate-legacy-h2.sh} 等部署脚本在 Spring 上下文之外读取
 * H2 file-mode 数据库的 schema fingerprint。</p>
 *
 * <h3>设计取舍</h3>
 * <p>原 {@code SchemaFingerprint} helper 是 {@code src/test/java/...} 下的
 * package-private 测试工具 — main 代码不能直接引用 test 编译产物。本类在
 * main 包内复刻相同的 V1 冻结表名清单与读取逻辑（{@link #V1_TABLES} 与
 * {@link #readKylinTables(JdbcTemplate)}），与 P3-T5 test helper 保持 1:1
 * 行为一致；任何 V1 表名变更必须同步更新两边。</p>
 *
 * <h3>使用</h3>
 * <pre>
 *   java -cp "target/classes:&lt;deps&gt;" com.kylinops.migration.SchemaFingerprintMain &lt;h2-db-file-path&gt;
 * </pre>
 *
 * <h3>输出</h3>
 * <ul>
 *   <li>成功：纯文本 SHA-256 哈希（64 hex chars）→ stdout，可直接被 bash 捕获</li>
 *   <li>失败：可读错误消息 → stderr</li>
 * </ul>
 *
 * <h3>退出码</h3>
 * <ul>
 *   <li>0 = 成功（hash 已输出）</li>
 *   <li>1 = 连接/读取/哈希异常</li>
 *   <li>2 = 参数错误（缺失/文件不存在）</li>
 * </ul>
 */
public final class SchemaFingerprintMain {

    /**
     * V1__legacy_schema.sql 冻结的 8 张 legacy 表名（H2 PG 模式大写存储）。
     *
     * <p>与 {@code src/test/java/com/kylinops/migration/SchemaFingerprint.V1_TABLES}
     * 严格保持一致 — V1 表名变更必须同步更新两边。</p>
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

    private SchemaFingerprintMain() {
        // utility class
    }

    public static void main(String[] args) {
        if (args == null || args.length == 0 || args[0] == null || args[0].isBlank()) {
            System.err.println("用法: java com.kylinops.migration.SchemaFingerprintMain <h2-db-file-path>");
            System.err.println("示例: java ... SchemaFingerprintMain /var/lib/kylinops/data/kylinops.mv.db");
            System.exit(2);
        }

        // 去掉 .mv.db / .trace.db 后缀得到 H2 database name（H2 自动附加 .mv.db, 不能出现在 URL 中）
        String dbFile = stripH2Extension(args[0]);
        Path dbPath = resolveDbPath(dbFile + ".mv.db");
        if (!dbPath.toFile().isFile()) {
            System.err.println("[ERROR] H2 数据库文件不存在: " + dbPath.toAbsolutePath());
            System.exit(2);
        }

        // 用不含扩展名的路径作为 H2 database name
        Path dbNamePath = resolveDbPath(dbFile);
        String jdbcUrl = "jdbc:h2:file:" + dbNamePath.toAbsolutePath() + ";DB_CLOSE_DELAY=0";
        DriverManagerDataSource ds = new DriverManagerDataSource(jdbcUrl, "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        try {
            TreeSet<String> tables = new TreeSet<>(readKylinTables(jdbc));
            String joined = String.join("\n", tables);
            String hash = sha256Hex(joined);
            System.out.println(hash);
            System.exit(0);
        } catch (Exception ex) {
            System.err.println("[ERROR] 无法读取 schema fingerprint: " + ex.getClass().getSimpleName()
                    + ": " + ex.getMessage());
            System.exit(1);
        }
    }

    /**
     * 去掉 .mv.db / .trace.db 后缀，使 H2 JDBC URL 正确指向数据库名。
     */
    private static String stripH2Extension(String raw) {
        if (raw.endsWith(".mv.db")) {
            return raw.substring(0, raw.length() - 6);
        }
        if (raw.endsWith(".trace.db")) {
            return raw.substring(0, raw.length() - 9);
        }
        return raw;
    }

    private static Path resolveDbPath(String raw) {
        File f = new File(raw);
        if (f.isAbsolute()) {
            return Paths.get(raw).toAbsolutePath();
        }
        return Paths.get(raw).toAbsolutePath();
    }

    /**
     * 读取当前数据库中所有 {@code KYLIN_*} 表名（H2 PG 模式大写）。
     *
     * <p>排除 V2/V3 增量迁移新增的表：
     * <ul>
     *   <li>V2: {@code KYLIN_EXECUTION_ATTEMPT} / {@code KYLIN_EXECUTION_OUTCOME}</li>
     *   <li>V3: {@code KYLIN_LLM_CALL_RECORD}</li>
     * </ul>
     *
     * <p>与 {@code src/test/java/com/kylinops/migration/SchemaFingerprint.readKylinTables}
     * 行为一致 — 表名变更必须同步更新两边。</p>
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
     * 列出 V1 冻结表名（与 {@link #V1_TABLES} 等价，便于诊断输出）。
     */
    static List<String> v1TableNames() {
        return Arrays.asList(V1_TABLES.toArray(new String[0]));
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 在 JDK 17+ 必有
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}