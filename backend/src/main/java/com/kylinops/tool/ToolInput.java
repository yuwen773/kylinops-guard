package com.kylinops.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 工具调用输入 POJO
 * <p>
 * 封装一次 OpsTool 调用的所有入参，由 ToolExecutor 构造并传递给 OpsTool.execute()。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ToolInput {

    /** 目标工具名称 */
    private String toolName;

    /** 工具调用参数（键值对） */
    private Map<String, Object> params;

    /** 请求追踪 ID（关联 auditId，贯穿全链路） */
    private String requestId;
}
