package com.kylinops.inspection;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 巡检调度器线程池配置(P1-02 Task 6)。
 *
 * <p>提供专用 {@link ThreadPoolTaskScheduler} Bean,避免与 Spring Boot 默认调度器共享线程池
 * (后者用于 @Scheduled / @Async 任务,可能被业务逻辑占用)。</p>
 *
 * <p>线程池参数:</p>
 * <ul>
 *   <li>poolSize=2 — 巡检触发与恢复各 1 个并发足够(顺序扫描,大量并发反而增加 DB 负载)
 *   <li>awaitTermination=5s — 应用关闭时给执行中的任务最多 5s 收尾
 *   <li>waitForTasksToCompleteOnShutdown=true — 让开始的任务跑完,避免半截执行
 * </ul>
 */
@Configuration
public class InspectionSchedulingConfig {

    @Bean(name = "inspectionTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler inspectionTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("inspection-sched-");
        scheduler.setAwaitTerminationSeconds(5);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.initialize();
        return scheduler;
    }
}