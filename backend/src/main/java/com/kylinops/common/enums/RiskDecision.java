package com.kylinops.common.enums;

/**
 * 风险决策结果
 * <p>
 * RiskCheckService 对请求进行安全评估后，输出以下决策之一：
 * <ul>
 *   <li>{@link #ALLOW} — 允许执行（L0/L1）</li>
 *   <li>{@link #CONFIRM} — 需用户确认后执行（L2）</li>
 *   <li>{@link #BLOCK} — 直接阻断（L3/L4）</li>
 * </ul>
 * </p>
 */
public enum RiskDecision {

    /** 允许执行 */
    ALLOW,

    /** 需用户确认 */
    CONFIRM,

    /** 直接阻断 */
    BLOCK
}
