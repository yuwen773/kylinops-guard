package com.kylinops.common.enums;

/**
 * 安全风险等级
 * <p>
 * 每个注册的 OpsTool 必须声明其 riskLevel。
 * RiskCheckService 根据此等级决定操作策略：
 * <ul>
 *   <li>L0 — 信息查询，无风险，直接 ALLOW，不审计</li>
 *   <li>L1 — 轻度风险，直接 ALLOW，需审计</li>
 *   <li>L2 — 中等风险，需用户确认 (CONFIRM)</li>
 *   <li>L3 — 高风险，直接 BLOCK</li>
 *   <li>L4 — 严重风险，直接 BLOCK（含绝对禁止命令列表）</li>
 * </ul>
 * </p>
 */
public enum RiskLevel {

    /** 信息查询 — 无风险，直接允许，不审计 */
    L0,

    /** 轻度风险 — 直接允许，但需记录审计日志 */
    L1,

    /** 中等风险 — 需用户确认，生成 PendingAction */
    L2,

    /** 高风险 — 直接阻断，记录审计日志 */
    L3,

    /** 严重风险 — 绝对阻断（含 rm -rf / 等），记录审计日志 */
    L4
}
