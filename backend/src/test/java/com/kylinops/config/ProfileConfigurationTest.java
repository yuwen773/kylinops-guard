package com.kylinops.config;

import com.kylinops.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 dev/test/prod profile 的配置分离。
 * <p>
 * 覆盖要点：
 * <ul>
 *   <li>dev — H2 文件模式 + console 可选</li>
 *   <li>test — H2 内存模式 + Flyway</li>
 *   <li>prod — PostgreSQL + lob_type=TEXT + 无 H2 回退</li>
 *   <li>MockTool / FailingMockTool 仅在 dev/test 注册</li>
 * </ul>
 *
 * <p>prod profile 测试使用 H2 替代 PostgreSQL 数据源以支持
 * 本地运行（无需 Docker / 真实 PG 实例）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProfileConfigurationTest {

    /**
     * 文件级验证：application-prod.yml 必须包含 {@code lob_type: TEXT}。
     * <p>H2 无法兼容 {@code TEXT} + {@code @Lob} 的 JPA 校验，因此运行时
     * prod profile 测试须临时覆盖为 {@code CLOB}。此测试直接从文件内容
     * 抓取，确保部署产物（application-prod.yml）有正确的占位符配置。</p>
     */
    @Test
    @DisplayName("application-prod.yml 文件中包含 lob_type: TEXT（文件级验证）")
    void prodYamlHasLobTypeText() throws Exception {
        String yaml;
        try (InputStream is = getClass().getResourceAsStream("/application-prod.yml")) {
            assertThat(is).as("application-prod.yml 须在 classpath").isNotNull();
            yaml = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
        }
        assertThat(yaml)
                .as("application-prod.yml 必须包含 lob_type: TEXT（而非 CLOB）")
                .contains("lob_type: TEXT");
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("dev")
    @DisplayName("dev profile 使用 H2 文件数据库")
    class DevProfileUsesH2File {
        @Autowired
        private Environment env;

        @Test
        void datasourceShouldBeH2() {
            String url = env.getProperty("spring.datasource.url");
            assertThat(url)
                    .as("dev 应使用 H2 文件模式")
                    .contains("jdbc:h2:file:");
        }

        @Test
        void h2ConsoleShouldBeEnabled() {
            String consoleEnabled = env.getProperty("spring.h2.console.enabled");
            assertThat(consoleEnabled).isEqualTo("true");
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @DisplayName("test profile 使用 H2 内存数据库 + Flyway")
    class TestProfileUsesH2Mem {
        @Autowired
        private Environment env;

        @Test
        void datasourceShouldBeH2Mem() {
            String url = env.getProperty("spring.datasource.url");
            assertThat(url)
                    .as("test 应使用 H2 内存模式")
                    .contains("jdbc:h2:mem:");
        }

        @Test
        void flywayShouldBeEnabled() {
            String flywayEnabled = env.getProperty("spring.flyway.enabled");
            assertThat(flywayEnabled).isEqualTo("true");
        }

        @Test
        void ddlAutoShouldBeValidate() {
            String ddlAuto = env.getProperty("spring.jpa.hibernate.ddl-auto");
            assertThat(ddlAuto).isEqualTo("validate");
        }

        @Test
        void lobTypeShouldBeCLOB() {
            String lobType = env.getProperty("spring.flyway.placeholders.lob_type");
            assertThat(lobType)
                    .as("test profile 的 lob_type 应为 CLOB（H2 兼容）")
                    .isEqualTo("CLOB");
        }
    }

    /**
     * prod profile 测试：使用 H2 内嵌 + lob_type=CLOB（H2 兼容）。
     * application-prod.yml 的原始内容（含 lob_type=TEXT）通过
     * {@link #prodYamlHasLobTypeText()} 单独验证。
     */
    @Nested
    @SpringBootTest(properties = {
            "spring.datasource.url=jdbc:h2:mem:kylinops-prod-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=sa",
            "kylinops.admin.password-hash=$2a$10$test",
            "spring.flyway.placeholders.lob_type=CLOB"
    })
    @ActiveProfiles("prod")
    @DisplayName("prod profile 配置验证")
    class ProdProfileConfig {
        @Autowired
        private Environment env;

        @Test
        void flywayShouldBeEnabled() {
            String flywayEnabled = env.getProperty("spring.flyway.enabled");
            assertThat(flywayEnabled).isEqualTo("true");
        }

        @Test
        void ddlAutoShouldBeValidate() {
            String ddlAuto = env.getProperty("spring.jpa.hibernate.ddl-auto");
            assertThat(ddlAuto).isEqualTo("validate");
        }

        @Test
        void h2ConsoleShouldBeDisabled() {
            String consoleEnabled = env.getProperty("spring.h2.console.enabled");
            assertThat(consoleEnabled).isEqualTo("false");
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("dev")
    @DisplayName("dev profile 下 MockTool 已注册")
    class DevProfileHasMockTool {
        @Autowired(required = false)
        private ToolRegistry toolRegistry;

        @Test
        void mockToolShouldBeRegistered() {
            assertThat(toolRegistry).isNotNull();
            assertThat(toolRegistry.contains("mock_tool"))
                    .as("dev profile 应注册 mock_tool")
                    .isTrue();
            assertThat(toolRegistry.contains("failing_mock_tool"))
                    .as("dev profile 应注册 failing_mock_tool")
                    .isTrue();
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @DisplayName("test profile 下 MockTool 已注册")
    class TestProfileHasMockTool {
        @Autowired(required = false)
        private ToolRegistry toolRegistry;

        @Test
        void mockToolShouldBeRegistered() {
            assertThat(toolRegistry).isNotNull();
            assertThat(toolRegistry.contains("mock_tool"))
                    .as("test profile 应注册 mock_tool")
                    .isTrue();
            assertThat(toolRegistry.contains("failing_mock_tool"))
                    .as("test profile 应注册 failing_mock_tool")
                    .isTrue();
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "spring.datasource.url=jdbc:h2:mem:kylinops-prod-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=sa",
            "kylinops.admin.password-hash=$2a$10$test",
            "spring.flyway.placeholders.lob_type=CLOB"
    })
    @ActiveProfiles("prod")
    @DisplayName("prod profile 下 MockTool 不应注册")
    class ProdProfileNoMockTool {
        @Autowired(required = false)
        private ToolRegistry toolRegistry;

        @Test
        void mockToolShouldNotBeRegistered() {
            assertThat(toolRegistry).as("prod profile 应加载 ToolRegistry").isNotNull();
            assertThat(toolRegistry.contains("mock_tool"))
                    .as("prod profile 不应注册 mock_tool")
                    .isFalse();
            assertThat(toolRegistry.contains("failing_mock_tool"))
                    .as("prod profile 不应注册 failing_mock_tool")
                    .isFalse();
        }
    }
}
