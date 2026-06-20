package com.kylinops.inspection;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 巡检配置:独立于 SafeExecutor 白名单,因为读 + 调度关注点不同。
 *
 * <p>{@code kylinops.inspection.allowed-services} 是 SERVICE 模板校验时可接受的
 * systemd service 名(防止任意服务名落到 journal_log 工具里被滥用为日志采集范围)。</p>
 *
 * <p>{@code kylinops.inspection.scheduler.poll-interval} 控制调度器轮询周期;
 * {@code kylinops.inspection.recovery.abandoned-threshold} 决定 RUNNING 执行何时被视为 abandoned。</p>
 */
@ConfigurationProperties(prefix = "kylinops.inspection")
public class InspectionProperties {

    /**
     * 允许出现在巡检模板参数里的 systemd service 名集合。
     * 空列表 = 拒绝所有 serviceName(安全默认,部署方按需放开)。
     */
    private List<String> allowedServices = new ArrayList<>();

    /** 调度器配置(轮询周期)。 */
    private Scheduler scheduler = new Scheduler();

    /** 启动期 / 周期恢复配置。 */
    private Recovery recovery = new Recovery();

    public List<String> getAllowedServices() {
        return Collections.unmodifiableList(allowedServices);
    }

    public void setAllowedServices(List<String> allowedServices) {
        this.allowedServices = allowedServices == null
                ? new ArrayList<>()
                : new ArrayList<>(allowedServices);
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    public void setScheduler(Scheduler scheduler) {
        this.scheduler = scheduler == null ? new Scheduler() : scheduler;
    }

    public Recovery getRecovery() {
        return recovery;
    }

    public void setRecovery(Recovery recovery) {
        this.recovery = recovery == null ? new Recovery() : recovery;
    }

    /** 调度器配置(轮询周期)。 */
    public static class Scheduler {
        /** 调度器扫描周期(默认 60s)。 */
        private Duration pollInterval = Duration.ofSeconds(60);

        /** ApplicationReadyEvent 是否自动启动周期调度(默认 true;测试可关闭避免污染测试断言)。 */
        private boolean autoStart = true;

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval == null ? Duration.ofSeconds(60) : pollInterval;
        }

        public boolean isAutoStart() {
            return autoStart;
        }

        public void setAutoStart(boolean autoStart) {
            this.autoStart = autoStart;
        }
    }

    /** 启动期 / 周期恢复配置。 */
    public static class Recovery {
        /** RUNNING 执行超过该阈值被视为 abandoned(默认 1h)。 */
        private Duration abandonedThreshold = Duration.ofHours(1);

        public Duration getAbandonedThreshold() {
            return abandonedThreshold;
        }

        public void setAbandonedThreshold(Duration abandonedThreshold) {
            this.abandonedThreshold = abandonedThreshold == null
                    ? Duration.ofHours(1) : abandonedThreshold;
        }
    }
}