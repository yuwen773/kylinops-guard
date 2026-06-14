package com.kylinops.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway H2 迁移冒烟测试
 *
 * <p>验证 Flyway 在 H2 PostgreSQL 模式下成功应用 V1 基线迁移：
 * <ul>
 *   <li>flyway_schema_history 表存在且至少有一条已成功执行的记录</li>
 *   <li>8 张 legacy 表（kylin_session / kylin_message / kylin_audit_log /
 *       kylin_tool_definition / kylin_tool_call_record / kylin_risk_check_record /
 *       kylin_pending_action / kylin_report）由 JPA validate 通过</li>
 * </ul>
 * </p>
 *
 * <p>本测试为 Phase 1 Runtime/Database 强化的回归保护：
 * 一旦 Flyway 配置错误或迁移脚本缺列，Spring Boot 启动即失败。</p>
 */
@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@ActiveProfiles("test")
class FlywayH2MigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayCreatesLegacyTables() {
        // Flyway 应在启动时自动建立历史表并至少成功执行 V1
        Integer historyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"success\" = TRUE",
                Integer.class);
        assertThat(historyCount)
                .as("Flyway 至少应成功执行一条迁移（V1__legacy_schema.sql）")
                .isPositive();
    }

    @Test
    void flywayHistoryTableExists() {
        // 直接验证历史表存在（不依赖具体迁移版本；用 UPPER 兼容 H2 的大小写折叠）
        Long tableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE UPPER(table_name) = 'FLYWAY_SCHEMA_HISTORY'",
                Long.class);
        assertThat(tableCount)
                .as("Flyway 历史表必须存在")
                .isEqualTo(1L);
    }
}