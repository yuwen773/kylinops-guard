package com.kylinops.executor;

import com.kylinops.common.ApiResponse;
import com.kylinops.common.BusinessException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 动作确认 API
 * <p>
 * 提供 POST /api/actions/confirm 端点，用于确认或取消 L2 待确认动作。
 * 请求体 <b>只接受 actionId 和 confirm</b>，不接受命令或参数替换。
 * </p>
 *
 * <h3>安全约束</h3>
 * <ul>
 *   <li>无法通过请求体注入新工具名或命令</li>
 *   <li>所有参数由服务端 PendingAction 实体中读取</li>
 *   <li>确认后再次执行风险校验，防范确认后的风险变化</li>
 *   <li>已处理/过期/不存在的动作返回业务错误</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/actions")
@RequiredArgsConstructor
public class ActionConfirmController {

    private final ActionConfirmService actionConfirmService;

    @PostMapping("/confirm")
    public ApiResponse<PendingAction> confirmAction(@Valid @RequestBody ActionConfirmRequest request) {
        log.info("动作确认请求: actionId={}, confirm={}", request.getActionId(), request.getConfirm());

        try {
            PendingAction result = actionConfirmService.confirmAction(
                    request.getActionId(), request.getConfirm());
            return ApiResponse.success(result);
        } catch (IllegalArgumentException e) {
            throw BusinessException.notFound(e.getMessage());
        } catch (IllegalStateException e) {
            throw BusinessException.badRequest(e.getMessage());
        }
    }
}
