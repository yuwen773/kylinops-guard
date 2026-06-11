package com.kylinops.audit;

import com.kylinops.common.ApiResponse;
import com.kylinops.common.BusinessException;
import com.kylinops.common.enums.AuditStatus;
import com.kylinops.common.enums.RiskLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * 审计日志 API
 * <p>
 * 提供审计日志的列表查询和详情查看接口。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    /**
     * 查询审计日志列表（支持分页和组合筛选）。
     *
     * @param riskLevel 风险等级（可选）
     * @param status    状态（可选）
     * @param keyword   关键词（可选，匹配 userInput）
     * @param startTime 开始时间（可选，ISO格式）
     * @param endTime   结束时间（可选，ISO格式）
     * @param page      页码（从0开始，默认0）
     * @param size      每页条数（默认20）
     * @return 分页的审计日志列表
     */
    @GetMapping("/logs")
    public ApiResponse<Page<AuditLogSummary>> listLogs(
            @RequestParam(required = false) RiskLevel riskLevel,
            @RequestParam(required = false) AuditStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        log.debug("查询审计日志: riskLevel={}, status={}, keyword={}, page={}, size={}",
                riskLevel, status, keyword, page, size);

        Page<AuditLogSummary> result = auditLogService.queryLogs(
                riskLevel, status, keyword, startTime, endTime, page, size);

        return ApiResponse.success(result);
    }

    /**
     * 获取审计日志详情（含关联的工具调用、风险校验和执行记录）。
     *
     * @param auditId 审计 ID
     * @return 审计详情
     */
    @GetMapping("/logs/{auditId}")
    public ApiResponse<AuditLogDetail> getLogDetail(@PathVariable String auditId) {
        log.debug("查询审计详情: auditId={}", auditId);

        return auditLogService.getDetail(auditId)
                .map(ApiResponse::success)
                .orElseThrow(() -> BusinessException.notFound("审计日志不存在: " + auditId));
    }
}
