package com.kylinops.executor;

import com.kylinops.common.ApiResponse;
import com.kylinops.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
 *   <li>跨认证会话的确认请求 → 403（归属校验），见 {@link AuthenticatedOperator}</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/actions")
@RequiredArgsConstructor
public class ActionConfirmController {

    private final ActionConfirmService actionConfirmService;

    @PostMapping("/confirm")
    public ApiResponse<PendingAction> confirmAction(@Valid @RequestBody ActionConfirmRequest request,
                                                     HttpServletRequest servletRequest) {
        AuthenticatedOperator operator = extractOperator(servletRequest);
        log.info("动作确认请求: actionId={}, confirm={}, operator={}",
                request.getActionId(), request.getConfirm(), operator.principal());

        try {
            PendingAction result = actionConfirmService.confirmAction(
                    request.getActionId(), request.getConfirm(), operator);
            return ApiResponse.success(result);
        } catch (IllegalArgumentException e) {
            throw BusinessException.notFound(e.getMessage());
        } catch (IllegalStateException e) {
            throw BusinessException.badRequest(e.getMessage());
        }
    }

    /**
     * 从 HTTP 请求上下文中提取已认证操作者身份。
     * <p>
     * principal 来自 Spring SecurityContext（由 P2-T2 登录流程建立），
     * authSessionId 来自 HttpSession（由 Tomcat 管理）。
     * 不从请求 JSON 读取——防伪造。
     * </p>
     */
    private static AuthenticatedOperator extractOperator(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String principal = auth != null && auth.isAuthenticated()
                ? auth.getName() : "anonymous";
        HttpSession session = request.getSession(false);
        String authSessionId = session != null ? session.getId() : "anonymous";
        return new AuthenticatedOperator(principal, authSessionId);
    }
}
