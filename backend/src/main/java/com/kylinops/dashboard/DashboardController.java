package com.kylinops.dashboard;

import com.kylinops.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard REST 控制器
 * <p>
 * 单一入口：{@code GET /api/dashboard/overview} — 触发一次系统概览采集，返回
 * 健康分、覆盖率、degraded 标志、关联 auditId 以及每个工具的指标详情。
 * </p>
 *
 * <h3>降级策略</h3>
 * 单个工具执行失败由 {@link DashboardService} 内部捕获并标记为
 * {@code status="failed"}；{@link DashboardService#refresh()} 不会向 Controller
 * 抛异常，因此即便所有工具失败接口仍返回 HTTP 200 + {@code degraded=true}。
 * 真正的异常（如服务整体不可用）由
 * {@link com.kylinops.common.GlobalExceptionHandler} 兜底为 HTTP 500。
 */
@Slf4j
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * 获取系统概览。
     *
     * @return Dashboard 概览数据
     */
    @GetMapping("/overview")
    public ApiResponse<DashboardOverview> getOverview() {
        log.debug("Dashboard 概览请求触发，开始刷新采集");
        DashboardOverview overview = dashboardService.refresh();
        return ApiResponse.success(overview);
    }
}