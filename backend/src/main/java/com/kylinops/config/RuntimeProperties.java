package com.kylinops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 运行时属性 — 命令执行器硬边界配置
 * <p>
 * 对应 {@code kylinops.runtime.*} 配置项。
 * 控制 OS 命令执行的最大并发、队列容量、输出上限和清理超时。
 * 所有 OS 工具均通过此 bean 读取运行时限制。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "kylinops.runtime")
public class RuntimeProperties {

    /** 最大并发进程数（默认 8） */
    private int maxProcesses = 8;

    /** 等待队列容量（默认 32） */
    private int queueCapacity = 32;

    /** 每条流最大行数（默认 1000） */
    private int maxLinesPerStream = 1000;

    /** 每条流最大字节数（默认 1 MB） */
    private int maxBytesPerStream = 1_048_576;

    /** 优雅终止等待时间（默认 250ms） */
    private Duration gracefulKill = Duration.ofMillis(250);

    /** 清理预算（默认 1s） */
    private Duration cleanupBudget = Duration.ofSeconds(1);
}
