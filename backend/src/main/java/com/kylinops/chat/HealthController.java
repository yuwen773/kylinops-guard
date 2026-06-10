package com.kylinops.chat;

import com.kylinops.common.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查控制器
 * <p>
 * 提供服务存活检测和基本信息查询。
 * P0 阶段首个端点，标志后端基础框架就绪。
 * </p>
 */
@RestController
public class HealthController {

    @Value("${kylinops.version:0.1.0}")
    private String appVersion;

    @Value("${kylinops.app.name:麒麟安全智能运维 Agent}")
    private String appName;

    @Value("${spring.application.name:kylin-ops-guard}")
    private String serviceName;

    /**
     * GET /api/health — 服务健康检查
     * <p>
     * 返回服务状态、版本号、运行时信息。
     * 可用于负载均衡健康探测或 Docker HEALTHCHECK。
     * </p>
     */
    @GetMapping(value = "/api/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("status", "UP");
        info.put("service", serviceName);
        info.put("appName", appName);
        info.put("version", appVersion);
        info.put("timestamp", Instant.now().toString());

        // Java / JVM 信息
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("javaVersion", System.getProperty("java.version"));
        jvm.put("availableProcessors", runtime.availableProcessors());
        jvm.put("totalMemory", runtime.totalMemory());
        jvm.put("freeMemory", runtime.freeMemory());
        jvm.put("maxMemory", runtime.maxMemory());
        info.put("jvm", jvm);

        return ApiResponse.success(info, "服务运行正常");
    }
}
