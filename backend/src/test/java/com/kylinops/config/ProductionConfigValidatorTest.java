package com.kylinops.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ProductionConfigValidator 在生产/非生产 profile 下的启停行为。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>prod profile 启动时验证必需配置项（DB URL/user/password + admin BCrypt hash）</li>
 *   <li>非 prod profile（dev/test）不应注册验证器</li>
 *   <li>验证器直接单元测试（通过反射设值）</li>
 * </ul>
 * </p>
 */
class ProductionConfigValidatorTest {

    // ==================== 单元测试：直接验证校验逻辑 ====================

    @Nested
    @DisplayName("直接验证 ProductionConfigValidator.afterPropertiesSet()")
    class DirectValidationTest {

        @Test
        @DisplayName("缺 datasource.url 时抛 IllegalStateException")
        void missingDbUrl_throws() {
            ProductionConfigValidator validator = createValidator("", "user", "pass", "hash");
            assertThatThrownBy(validator::afterPropertiesSet)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.datasource.url");
        }

        @Test
        @DisplayName("缺 datasource.username 时抛 IllegalStateException")
        void missingDbUser_throws() {
            ProductionConfigValidator validator = createValidator("jdbc:postgresql://localhost:5432/db", "", "pass", "hash");
            assertThatThrownBy(validator::afterPropertiesSet)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.datasource.username");
        }

        @Test
        @DisplayName("缺 datasource.password 时抛 IllegalStateException")
        void missingDbPassword_throws() {
            ProductionConfigValidator validator = createValidator("jdbc:postgresql://localhost:5432/db", "user", "", "hash");
            assertThatThrownBy(validator::afterPropertiesSet)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.datasource.password");
        }

        @Test
        @DisplayName("缺 admin.password-hash 时抛 IllegalStateException")
        void missingAdminHash_throws() {
            ProductionConfigValidator validator = createValidator("jdbc:postgresql://localhost:5432/db", "user", "pass", "");
            assertThatThrownBy(validator::afterPropertiesSet)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("kylinops.admin.password-hash");
        }

        @Test
        @DisplayName("全配置时验证通过")
        void allConfigPresent_passes() {
            ProductionConfigValidator validator = createValidator(
                    "jdbc:postgresql://localhost:5432/db", "user", "pass",
                    "$2a$10$dummyhash");
            // afterPropertiesSet 不应抛异常
            validator.afterPropertiesSet();
        }

        @Test
        @DisplayName("LLM 启用时缺 base-url 抛异常")
        void llmEnabledMissingUrl_throws() {
            ProductionConfigValidator validator = createValidator(
                    "jdbc:postgresql://localhost:5432/db", "user", "pass",
                    "$2a$10$dummyhash", true, "", "key", "model");
            assertThatThrownBy(validator::afterPropertiesSet)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("kylinops.llm.base-url");
        }

        @Test
        @DisplayName("LLM 禁用时不影响校验")
        void llmDisabled_skipsValidation() {
            ProductionConfigValidator validator = createValidator(
                    "jdbc:postgresql://localhost:5432/db", "user", "pass",
                    "$2a$10$dummyhash", false, "", "", "");
            validator.afterPropertiesSet();
        }

        private ProductionConfigValidator createValidator(String dbUrl, String dbUser,
                                                           String dbPass, String adminHash) {
            return createValidator(dbUrl, dbUser, dbPass, adminHash, false, "", "", "");
        }

        private ProductionConfigValidator createValidator(String dbUrl, String dbUser,
                                                           String dbPass, String adminHash,
                                                           boolean llmEnabled, String llmUrl,
                                                           String llmKey, String llmModel) {
            ProductionConfigValidator v = new ProductionConfigValidator();
            setField(v, "dbUrl", dbUrl);
            setField(v, "dbUsername", dbUser);
            setField(v, "dbPassword", dbPass);
            setField(v, "adminPasswordHash", adminHash);
            setField(v, "llmEnabled", llmEnabled);
            setField(v, "llmBaseUrl", llmUrl);
            setField(v, "llmApiKey", llmKey);
            setField(v, "llmModel", llmModel);
            return v;
        }

        private void setField(Object target, String fieldName, Object value) {
            Field field = ReflectionUtils.findField(target.getClass(), fieldName);
            assertThat(field).as("字段 %s 存在", fieldName).isNotNull();
            field.setAccessible(true);
            ReflectionUtils.setField(field, target, value);
        }
    }

    // ==================== Profile 整合测试 ====================

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("Profile 整合测试")
    class ProfileIntegrationTest {

        @Nested
        @SpringBootTest(properties = {
                "kylinops.admin.password-hash=$2a$10$dummyhash",
                "spring.datasource.url=jdbc:h2:mem:kylinops-prod-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=sa",
                "spring.flyway.placeholders.lob_type=CLOB"
        })
        @ActiveProfiles("prod")
        @DisplayName("prod profile + 全配置 = 启动成功")
        class ProdCompleteConfigStartsSuccessfully {
            @Autowired(required = false)
            private ProductionConfigValidator validator;

            @Test
            void contextShouldLoad() {
                assertThat(validator)
                        .as("全配置时 context 应包含 ProductionConfigValidator")
                        .isNotNull();
            }
        }

        @Nested
        @SpringBootTest
        @ActiveProfiles("dev")
        @DisplayName("dev profile 不应注册 ProductionConfigValidator")
        class DevProfileSkipsValidation {
            @Autowired(required = false)
            private ProductionConfigValidator validator;

            @Test
            void validatorShouldNotBeRegistered() {
                assertThat(validator)
                        .as("dev profile 下 ProductionConfigValidator 不应注册")
                        .isNull();
            }
        }

        @Nested
        @SpringBootTest
        @ActiveProfiles("test")
        @DisplayName("test profile 不应注册 ProductionConfigValidator")
        class TestProfileSkipsValidation {
            @Autowired(required = false)
            private ProductionConfigValidator validator;

            @Test
            void validatorShouldNotBeRegistered() {
                assertThat(validator)
                        .as("test profile 下 ProductionConfigValidator 不应注册")
                        .isNull();
            }
        }
    }
}
