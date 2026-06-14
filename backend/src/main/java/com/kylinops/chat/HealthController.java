package com.kylinops.chat;

import com.kylinops.common.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查控制器
 * <p>
 * 提供三个层级的健康探测：
 * <ul>
 *   <li>{@code GET /api/health} — 兼容层，返回服务基本信息（始终 200）</li>
 *   <li>{@code GET /api/health/live} — 存活探针（始终 200）</li>
 *   <li>{@code GET /api/health/ready} — 就绪探针（仅 DB + 规则就绪时 200）</li>
 * </ul>
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

    private final ReadinessService readinessService;

    public HealthController(ReadinessService readinessService) {
        this.readinessService = readinessService;
    }

    /**
     * GET /api/health — 服务健康检查（兼容端点）
     * <p>
     * 返回服务状态、版本号、运行时信息。
     * 始终保持 200 响应，不反映就绪状态。
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

    /**
     * GET /api/health/live — 存活探针
     * <p>
     * Liveness probe：服务进程是否活着。始终保持 200。
     * </p>
     */
    @GetMapping(value = "/api/health/live", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> live() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("status", "UP");
        info.put("service", serviceName);
        info.put("timestamp", Instant.now().toString());
        return ApiResponse.success(info, "服务存活");
    }

    /**
     * GET /api/health/ready — 就绪探针
     * <p>
     * Readiness probe：仅当数据库和安全规则就绪时返回 200，
     * 否则返回 503 Service Unavailable。
     * </p>
     */
    @GetMapping(value = "/api/health/ready", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> ready() {
        ReadinessService.ReadinessDetail detail = readinessService.getDetail();

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("service", serviceName);
        info.put("timestamp", Instant.now().toString());
        info.put("database", detail.database());
        info.put("rules", detail.rules());

        if (detail.allReady()) {
            info.put("status", "UP");
            return ResponseEntity.ok(ApiResponse.success(info, "服务就绪"));
        }

        info.put("status", "DOWN");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.success(info, "服务未就绪: database=" + detail.database() + ", rules=" + detail.rules()));
    }
}
