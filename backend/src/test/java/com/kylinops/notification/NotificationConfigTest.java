package com.kylinops.notification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NotificationConfig 单元测试(无 Spring Context)。
 *
 * <p>绑定行为由 @SpringBootTest 集成覆盖(见其他测试类)。
 * 本测试只覆盖 POJO 行为 + 默认值,避免污染 @DataJpaTest 上下文查找。</p>
 */
class NotificationConfigTest {

    @Test
    void defaultsAreSafe() {
        NotificationConfig cfg = new NotificationConfig();
        assertFalse(cfg.isEnabled(), "默认 enabled=false(零外网)");
        assertFalse(cfg.isDryRun());
        assertNotNull(cfg.getChannels());
        assertTrue(cfg.getChannels().isEmpty());
    }

    @Test
    void channelConfig_effectiveTimeoutMs_defaultsTo3000() {
        NotificationConfig.ChannelConfig cc = NotificationConfig.ChannelConfig.builder()
                .id("ch-1").type(ChannelType.WEBHOOK).enabled(true)
                .url("https://x").build();
        assertEquals(3000, cc.effectiveTimeoutMs());
        cc.setTimeoutMs(0);
        assertEquals(3000, cc.effectiveTimeoutMs());
        cc.setTimeoutMs(5000);
        assertEquals(5000, cc.effectiveTimeoutMs());
        cc.setTimeoutMs(null);
        assertEquals(3000, cc.effectiveTimeoutMs());
        cc.setTimeoutMs(-1);
        assertEquals(3000, cc.effectiveTimeoutMs());
    }

    @Test
    void channelConfig_holdsAllFields() {
        NotificationConfig.ChannelConfig cc = NotificationConfig.ChannelConfig.builder()
                .id("webhook-prod").type(ChannelType.WEBHOOK)
                .enabled(true).url("https://example.com/hook")
                .secret("s3cret").timeoutMs(2500).build();
        assertEquals("webhook-prod", cc.getId());
        assertEquals(ChannelType.WEBHOOK, cc.getType());
        assertTrue(cc.isEnabled());
        assertEquals("https://example.com/hook", cc.getUrl());
        assertEquals("s3cret", cc.getSecret());
        assertEquals(2500, cc.getTimeoutMs());
    }

    @Test
    void channelsList_roundTrip() {
        NotificationConfig local = new NotificationConfig();
        local.setEnabled(true);
        local.setDryRun(true);
        local.setChannels(java.util.List.of(
                NotificationConfig.ChannelConfig.builder()
                        .id("ch-A").type(ChannelType.WEBHOOK).url("https://a")
                        .secret("sa").timeoutMs(1500).build(),
                NotificationConfig.ChannelConfig.builder()
                        .id("ch-B").type(ChannelType.FEISHU).build()));
        assertTrue(local.isEnabled());
        assertTrue(local.isDryRun());
        assertEquals(2, local.getChannels().size());
        assertEquals("ch-A", local.getChannels().get(0).getId());
        assertEquals(1500, local.getChannels().get(0).getTimeoutMs());
        assertEquals(ChannelType.FEISHU, local.getChannels().get(1).getType());
    }
}
