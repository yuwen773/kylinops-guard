package com.kylinops.inspection;

import com.kylinops.common.BusinessException;

/**
 * 巡检参数/计划校验失败异常(P1-02 Task 3)。
 *
 * <p>消息必须包含字段名以便 API 层直接透传给前端表单做字段级错误提示。
 * HTTP 状态码 400(Bad Request)。</p>
 */
public class InspectionValidationException extends BusinessException {

    public InspectionValidationException(String message) {
        super(400, message);
    }

    public InspectionValidationException(String message, Throwable cause) {
        super(400, message, cause);
    }
}