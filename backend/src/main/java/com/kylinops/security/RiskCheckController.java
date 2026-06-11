package com.kylinops.security;

import com.kylinops.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 风险校验 API
 * <p>
 * 提供独立的 POST /api/security/risk-check 端点，
 * 用于对命令/路径/工具进行安全风险评估。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/security")
@RequiredArgsConstructor
public class RiskCheckController {

    private final RiskCheckService riskCheckService;

    @PostMapping("/risk-check")
    public ApiResponse<RiskCheckResult> checkRisk(@Valid @RequestBody RiskCheckRequest request) {
        log.debug("风险校验请求: targetType={}, content={}", request.getTargetType(), request.getContent());

        RiskEvaluationContext ctx = new RiskEvaluationContext(
                request.getTargetType(),
                request.getContent(),
                request.getToolName(),
                null);

        String auditId = java.util.UUID.randomUUID().toString();
        RiskCheckResult result = riskCheckService.check(ctx, auditId);

        log.info("风险校验完成: level={}, decision={}, rules={}",
                result.getRiskLevel(), result.getDecision(), result.getMatchedRules());

        return ApiResponse.success(result).traceId(auditId);
    }
}
