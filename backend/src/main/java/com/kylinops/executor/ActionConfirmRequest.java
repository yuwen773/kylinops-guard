package com.kylinops.executor;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 动作确认请求 DTO
 * <p>
 * POST /api/actions/confirm 的请求体。
 * <b>只接受 actionId 和 confirm 两个字段，不接受工具名、命令或参数。</b>
 * </p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class ActionConfirmRequest {

    /** 待确认的动作 ID */
    @NotBlank(message = "动作 ID 不能为空")
    private String actionId;

    /** true=确认执行，false=取消 */
    @NotNull(message = "确认状态不能为空")
    private Boolean confirm;

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("unknown confirmation field: " + fieldName);
    }
}
