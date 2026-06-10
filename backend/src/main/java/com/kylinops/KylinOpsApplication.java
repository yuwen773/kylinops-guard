package com.kylinops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 麒麟安全智能运维 Agent — KylinOps Guard
 * <p>
 * 面向麒麟操作系统的安全可控智能运维 Agent。
 * 安全闭环：用户输入 → Agent 意图识别 → MCP-style Tool 调用
 *          → OS 感知 → RiskCheck → AuditLog → 返回结构化结果
 * </p>
 */
@SpringBootApplication
public class KylinOpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(KylinOpsApplication.class, args);
    }
}
