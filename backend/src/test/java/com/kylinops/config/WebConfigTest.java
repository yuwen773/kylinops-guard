package com.kylinops.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Web 配置测试
 * <p>
 * 验证 CORS 配置 Bean 正确加载。
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("WebConfig — CORS 配置")
class WebConfigTest {

    @Autowired(required = false)
    private WebConfig webConfig;

    @Test
    @DisplayName("WebConfig Bean 存在")
    void webConfigExists() {
        assertThat(webConfig).isNotNull();
    }
}
