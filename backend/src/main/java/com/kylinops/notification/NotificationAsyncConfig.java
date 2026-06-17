package com.kylinops.notification;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Notification 异步执行器配置。
 *
 * <p><b>关键不变量</b>:</p>
 * <ul>
 *   <li>AbortPolicy(禁止 CallerRunsPolicy — 会让主流程被通知拖慢)</li>
 *   <li>队列满不写 FAILED record(只 log error)</li>
 *   <li>不在主线程执行任何通知相关操作</li>
 *   <li>core=2, max=5, queue=100</li>
 * </ul>
 */
@Configuration
@EnableAsync
public class NotificationAsyncConfig {

    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("notification-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
