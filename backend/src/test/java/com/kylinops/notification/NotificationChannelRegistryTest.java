package com.kylinops.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NotificationChannelRegistry fail-open 行为测试。
 *
 * <p>test profile: enabled=false, channels=[]。registry 仍能正常初始化,不抛异常。</p>
 */
@SpringBootTest(classes = com.kylinops.KylinOpsApplication.class)
@ActiveProfiles("test")
class NotificationChannelRegistryTest {

    @Autowired
    private NotificationChannelRegistry registry;

    @Test
    void registry_isInitializedAndAvailableAsBean() {
        assertNotNull(registry);
        // 启动时无 channel bean(仅 @Component 实现还没注册) — fail-open, 不抛
    }

    @Test
    void resolveHandler_returnsEmptyForUnknownType() {
        NotificationChannelRegistry emptyRegistry = new NotificationChannelRegistry(List.of());
        emptyRegistry.init();

        Optional<NotificationChannel> result = emptyRegistry.resolveHandler(ChannelType.WEBHOOK);

        assertFalse(result.isPresent());
        assertFalse(emptyRegistry.hasAnyHandler());
    }

    @Test
    void hasAnyHandler_consistentWithRegisteredTypes() {
        boolean any = registry.hasAnyHandler();
        assertEquals(any, !registry.registeredTypes().isEmpty());
    }
}
