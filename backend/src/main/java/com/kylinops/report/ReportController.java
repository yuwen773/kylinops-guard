package com.kylinops.report;

import com.kylinops.common.ApiResponse;
import com.kylinops.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

/**
 * 报告 API。
 * <p>
 * 仅暴露生成 / 列表 / 详情三类只读接口；无 PUT / DELETE 端点。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    /**
     * 生成报告。
     * <p>
     * 请求体必须至少包含 {@code auditId} 或 {@code sessionId} 之一，否则返回 400。
     * </p>
     */
    @PostMapping("/generate")
    public ApiResponse<ReportDetail> generate(@RequestBody ReportGenerateRequest request) {
        log.debug("生成报告: auditId={}, sessionId={}, type={}",
                request != null ? request.getAuditId() : null,
                request != null ? request.getSessionId() : null,
                request != null ? request.getReportType() : null);
        try {
            ReportDetail detail = reportService.generate(request);
            return ApiResponse.success(detail);
        } catch (IllegalArgumentException ex) {
            log.warn("生成报告参数错误: {}", ex.getMessage());
            throw BusinessException.badRequest(ex.getMessage());
        }
    }

    /**
     * 分页查询报告列表。
     *
     * @param page 页码（从 0 开始，默认 0）
     * @param size 每页大小（默认 20，clamp 到 [1, 100]）
     */
    @GetMapping
    public ApiResponse<Page<ReportSummary>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.debug("查询报告列表: page={}, size={}", page, size);
        Page<ReportSummary> result = reportService.list(page, size);
        return ApiResponse.success(result);
    }

    /**
     * 按 reportId 查询报告详情。
     *
     * @param reportId 报告 ID
     */
    @GetMapping("/{reportId}")
    public ApiResponse<ReportDetail> getDetail(@PathVariable String reportId) {
        log.debug("查询报告详情: reportId={}", reportId);
        try {
            ReportDetail detail = reportService.getDetail(reportId);
            return ApiResponse.success(detail);
        } catch (IllegalArgumentException ex) {
            log.warn("报告不存在: {}", reportId);
            throw BusinessException.notFound(ex.getMessage());
        }
    }
}