package com.kylinops.inspection;

import java.util.List;

/**
 * 单次巡检的异常判定结果(P1-02 Task 5 — InspectionResultEvaluator 输出)。
 *
 * <p>三字段语义:</p>
 * <ul>
 *   <li>{@code abnormal} — 是否触发异常(true = 需走 ON_ABNORMAL / ALWAYS 通知策略)</li>
 *   <li>{@code keyToolFailed} — 关键工具是否失败(决定 execution.status 是否为 FAILED)</li>
 *   <li>{@code reasons} — 可读原因列表,按优先级排序:关键工具失败 > 阈值突破 > 非关键工具失败</li>
 * </ul>
 *
 * <p>不可变 record,字段在构造时复制以防外部修改。</p>
 */
public record AbnormalVerdict(boolean abnormal,
                              List<String> reasons,
                              boolean keyToolFailed) {

    public AbnormalVerdict {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public boolean isAbnormal() {
        return abnormal;
    }

    public List<String> getReasons() {
        return reasons;
    }

    public boolean isKeyToolFailed() {
        return keyToolFailed;
    }
}
