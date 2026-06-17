package com.kylinops.notification;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 通知通道注册中心 — <b>按 ChannelType 注册 handler</b>,不是按 channelId。
 *
 * <p><b>设计原则</b>:</p>
 * <ul>
 *   <li>Registry 按 {@link ChannelType} 注册 handler</li>
 *   <li>同一 ChannelType 多个 handler → log warn 保留第一个</li>
 *   <li>{@link NotificationDispatcher} 遍历 config.channels,根据 channelConfig.type 找 handler</li>
 * </ul>
 *
 * <p><b>启动策略:fail-open</b>(与 LlmToolContextPolicyRegistry 相反):</p>
 * <ul>
 *   <li>有 handler → log info</li>
 *   <li>无 handler(enabled=false 或未配置) → log warn,<b>不抛异常</b></li>
 *   <li>重复 type → 保留第一个 + log warn</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationChannelRegistry {

    private final List<NotificationChannel> channels;

    private final Map<ChannelType, NotificationChannel> handlerMap = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        for (NotificationChannel channel : channels) {
            ChannelType type = channel.type();
            if (type == null) {
                log.warn("通道 [{}] 缺少 type(),跳过注册", channel.getClass().getSimpleName());
                continue;
            }
            if (handlerMap.containsKey(type)) {
                log.warn("通道 type [{}] 重复,保留已有 handler: {},忽略: {}",
                        type,
                        handlerMap.get(type).getClass().getSimpleName(),
                        channel.getClass().getSimpleName());
                continue;
            }
            handlerMap.put(type, channel);
        }
        log.info("NotificationChannelRegistry 初始化完成: 共 {} 个 handler", handlerMap.size());
        if (log.isDebugEnabled()) {
            log.debug("已注册 handler: {}", handlerMap.keySet());
        }
    }

    /**
     * 按 ChannelType 查找 handler。
     */
    public Optional<NotificationChannel> resolveHandler(ChannelType type) {
        return Optional.ofNullable(handlerMap.get(type));
    }

    public boolean hasAnyHandler() {
        return !handlerMap.isEmpty();
    }

    /** 当前已注册的 ChannelType(只读) */
    public java.util.Set<ChannelType> registeredTypes() {
        return java.util.Collections.unmodifiableSet(handlerMap.keySet());
    }
}
