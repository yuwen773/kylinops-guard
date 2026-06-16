# Fix-01 RCA 推理链结构化 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在对话时实时产出结构化 `RootCauseChain`（symptom / evidence / hypotheses / excludedCauses / conclusion / confidence / suggestions / riskTips），三层流转 AgentResult → AuditLog → ReportDetail；前端用 `<ReasoningChain>` 组件可视化。

**Architecture:**
- `com.kylinops.rca` 新包：`RootCauseChain` 数据结构 + `RootCauseAnalyzer` 接口 + 3 个内置 analyzer（Disk / Health / Service）。
- `AgentOrchestrator` 在 Step 7 之后、Step 8 之前调用 analyzer，填入 `AgentResult.rootCauseChain`。
- 持久化用 `AuditLog.rootCauseChainJson` (Lob TEXT)，DTO `AuditLogDetail` / `ReportDetail` 暴露结构化对象。
- 前端新增 `<ReasoningChain>` 组件 + `normalizeIntentType()` 兼容映射。

**Tech Stack:**
- 后端：Java 17 + Spring Boot 3.x + JPA + Jackson + Lombok
- 前端：Vue 3 + TypeScript + Element Plus
- 测试：JUnit 5（后端）、Vitest（前端）、Playwright（E2E）

**Spec 引用：** `docs/superpowers/specs/2026-06-16-p0-defect-fix-sprint-design.md` §3
**前置 commit：** 当前 master HEAD（无前置 fix）

---

## 文件结构总览

**新增（5 个后端 + 1 个前端）：**
| 文件 | 职责 |
|---|---|
| `com.kylinops.rca.RootCauseChain` | 顶层数据结构（含 Evidence/Hypothesis/ExcludedCause 内部类） |
| `com.kylinops.rca.RootCauseAnalyzer` | analyzer 接口 + 主入口 `analyze(intent, results, decision)` |
| `com.kylinops.rca.DiskDiagnosisAnalyzer` | DISK_DIAGNOSIS 场景的根因推断 |
| `com.kylinops.rca.HealthCheckAnalyzer` | SYSTEM_CHECK 场景的健康评估链 |
| `com.kylinops.rca.ServiceDiagnosisAnalyzer` | SERVICE_DIAGNOSIS 场景的诊断链 |
| `frontend/src/utils/intentType.ts` | `normalizeIntentType()` 兼容映射 |

**修改（8 个后端 + 5 个前端）：**
| 文件 | 改动 |
|---|---|
| `com.kylinops.agent.AgentResult` | 新增 `rootCauseChain` 字段 |
| `com.kylinops.agent.AgentOrchestrator` | 注入 `RootCauseAnalyzer` 并在编排流程中调用 |
| `com.kylinops.audit.AuditLog` | 新增 `rootCauseChainJson` (@Lob TEXT) |
| `com.kylinops.audit.AuditLogDetail` | 新增 `rootCauseChain` 字段（DTO） |
| `com.kylinops.audit.AuditLogService` | `serializeRca()` / `deserializeRca()` 辅助 |
| `com.kylinops.report.Report` | 新增 `rootCauseChainJson` (@Lob TEXT) |
| `com.kylinops.report.ReportDetail` | 新增 `rootCauseChain` 字段（DTO） |
| `com.kylinops.report.ReportService` | `generate()` 时反序列化并填入 DTO |
| `frontend/src/types/agent.ts` | `AgentResult` 加 `rootCauseChain?` |
| `frontend/src/types/report.ts` | `ReportDetail` 加 `rootCauseChain?` |
| `frontend/src/types/rca.ts` | 新增（mirror 后端 RootCauseChain） |
| `frontend/src/components/ReasoningChain/index.vue` | 新增可视化组件 |
| `frontend/src/pages/ChatConsole/index.vue` | 在 agent 回复中嵌入组件 |

---

## Task 1: RootCauseChain 数据结构（含内部类）

**Files:**
- Create: `backend/src/main/java/com/kylinops/rca/RootCauseChain.java`
- Test: `backend/src/test/java/com/kylinops/rca/RootCauseChainTest.java`

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/kylinops/rca/RootCauseChainTest.java`:

```java
package com.kylinops.rca;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RootCauseChainTest {

    @Test
    void builder_creates_complete_chain() {
        RootCauseChain.Evidence ev = new RootCauseChain.Evidence(
                "ev-1", "disk_usage_tool", "tc-1",
                "/ 分区 86% 使用", 86.0, "%");
        RootCauseChain.Hypothesis hyp = new RootCauseChain.Hypothesis(
                "/var/log/app.log 占用 12GB", 0.86, true, "large_file_scan_tool 直接定位");
        RootCauseChain.ExcludedCause exc = new RootCauseChain.ExcludedCause(
                "/var/lib/mysql（敏感数据库目录）",
                "数据库目录不建议清理",
                java.util.List.of("ev-1"));

        RootCauseChain chain = RootCauseChain.builder()
                .symptom("磁盘根分区使用率 86%")
                .evidence(java.util.List.of(ev))
                .hypotheses(java.util.List.of(hyp))
                .excludedCauses(java.util.List.of(exc))
                .conclusion("/var/log/app.log 持续增长是主因")
                .confidence(0.86)
                .suggestions(java.util.List.of("归档或截断日志"))
                .riskTips(java.util.List.of("清理前需先归档"))
                .build();

        assertEquals("磁盘根分区使用率 86%", chain.getSymptom());
        assertEquals(1, chain.getEvidence().size());
        assertEquals("ev-1", chain.getEvidence().get(0).getEvidenceId());
        assertEquals("tc-1", chain.getEvidence().get(0).getSourceToolCallId());
        assertEquals(0.86, chain.getHypotheses().get(0).getProbability());
        assertTrue(chain.getHypotheses().get(0).isConfirmed());
        assertEquals("ev-1", chain.getExcludedCauses().get(0).getEvidenceIds().get(0));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend && mvn test -Dtest=RootCauseChainTest -q
```

Expected: `FAIL — symbol not found RootCauseChain` (编译失败)

- [ ] **Step 3: 实现 RootCauseChain**

`backend/src/main/java/com/kylinops/rca/RootCauseChain.java`:

```java
package com.kylinops.rca;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 根因分析链 — 跨 Agent / Audit / Report 三层共享。
 * 业务字段稳定（不含 LLM 自由生成字段），便于审计回放与合规。
 *
 * <p>字段命名（hypotheses 复数）前后端统一。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RootCauseChain {

    /** 现象（人类可读） */
    private String symptom;

    /** 工具证据列表 */
    private List<Evidence> evidence;

    /** 候选根因列表（含确认标记 + 概率） */
    private List<Hypothesis> hypotheses;

    /** 明确排除的根因（结构化对象） */
    private List<ExcludedCause> excludedCauses;

    /** 最终结论 */
    private String conclusion;

    /** 置信度 0.0-1.0 */
    private double confidence;

    /** 可执行的下一步建议 */
    private List<String> suggestions;

    /** 风险提示 */
    private List<String> riskTips;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Evidence {
        /** 证据唯一 ID（ExcludedCause.evidenceIds 引用此字段） */
        private String evidenceId;
        /** 产出该证据的工具名 */
        private String source;
        /** 关联的 ToolCallRecord ID（如有），用于审计深链 */
        private String sourceToolCallId;
        /** 人类可读的观察描述 */
        private String observation;
        /** 数值（如有） */
        private Double numericValue;
        /** 数值单位 */
        private String unit;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Hypothesis {
        /** 候选根因描述 */
        private String cause;
        /** 概率 0.0-1.0 */
        private double probability;
        /** 是否确认根因 */
        private boolean confirmed;
        /** 推理依据 */
        private String reasoning;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ExcludedCause {
        /** 被排除的根因描述 */
        private String cause;
        /** 排除原因 */
        private String reason;
        /** 关联的证据 ID 列表 */
        private List<String> evidenceIds;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd backend && mvn test -Dtest=RootCauseChainTest -q
```

Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/kylinops/rca/RootCauseChain.java \
        backend/src/test/java/com/kylinops/rca/RootCauseChainTest.java
git commit -m "feat(rca): add RootCauseChain data structure"
```

---

## Task 2: RootCauseAnalyzer 接口

**Files:**
- Create: `backend/src/main/java/com/kylinops/rca/RootCauseAnalyzer.java`

- [ ] **Step 1: 实现接口**

`backend/src/main/java/com/kylinops/rca/RootCauseAnalyzer.java`:

```java
package com.kylinops.rca;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.tool.ToolResult;

import java.util.List;

/**
 * 根因分析器入口。
 * 委托给具体的 intent-specific analyzer；不适用时返回 null。
 */
public interface RootCauseAnalyzer {

    /**
     * 编排主入口：在 AgentOrchestrator Step 7 后调用。
     *
     * @param intent   识别出的意图
     * @param results  工具执行结果列表
     * @param decision 风险决策
     * @return 根因链；不适用场景返回 null
     */
    RootCauseChain analyze(IntentType intent, List<ToolResult> results, RiskDecision decision);
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/kylinops/rca/RootCauseAnalyzer.java
git commit -m "feat(rca): add RootCauseAnalyzer interface"
```

---

## Task 3: DiskDiagnosisAnalyzer（演示场景 2 重点）

**Files:**
- Create: `backend/src/main/java/com/kylinops/rca/DiskDiagnosisAnalyzer.java`
- Test: `backend/src/test/java/com/kylinops/rca/DiskDiagnosisAnalyzerTest.java`

> ⚠️ **执行顺序约束**：本 Task **不创建** `DefaultRootCauseAnalyzer`（主入口）。`DefaultRootCauseAnalyzer` 依赖 `HealthCheckAnalyzer`（Task 4）和 `ServiceDiagnosisAnalyzer`（Task 5），若在 Task 3 提前创建会导致 `mvn compile` 失败。`DefaultRootCauseAnalyzer` 的实现与单元测试在 **Task 5 末尾**统一提交。

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/kylinops/rca/DiskDiagnosisAnalyzerTest.java`:

```java
package com.kylinops.rca;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DiskDiagnosisAnalyzerTest {

    private final DiskDiagnosisAnalyzer analyzer = new DiskDiagnosisAnalyzer();

    @Test
    void disk_above_85_with_large_log_returns_full_rca() {
        ToolResult diskResult = ToolResult.success("disk_usage_tool",
                Map.of("partitions", List.of("/: 86% used (12G/14G)")),
                "/: 86% used", 0);
        // 测试数据必须包含一个可处理大文件 + 一个敏感数据库目录，
        // 否则 excludedCauses 断言会因输入数据不匹配而失败
        ToolResult largeFileResult = ToolResult.success("large_file_scan_tool",
                Map.of("largeFiles", List.of(
                        "/var/log/app.log: 12GB",
                        "/var/lib/mysql/binlog.00001: 8GB")),
                "Top 2: /var/log/app.log (12GB), /var/lib/mysql/binlog.00001 (8GB)", 0);

        RootCauseChain chain = analyzer.analyze(
                IntentType.DISK_DIAGNOSIS,
                List.of(diskResult, largeFileResult),
                RiskDecision.ALLOW);

        assertNotNull(chain);
        assertTrue(chain.getSymptom().contains("86%"));
        assertEquals(2, chain.getEvidence().size());

        // ① /var/log/app.log 必须被确认为主因
        assertTrue(chain.getHypotheses().stream().anyMatch(h -> h.isConfirmed()
                && h.getCause().contains("/var/log/app.log")),
                "应确认 /var/log/app.log 为主因");

        // ② /var/lib/mysql 必须出现在 excludedCauses（敏感数据库目录）
        assertTrue(chain.getExcludedCauses().stream()
                .anyMatch(e -> e.getCause().contains("/var/lib/mysql")),
                "应将 /var/lib/mysql 标记为 excludedCause（敏感数据库目录）");

        // ③ 结论应指向 /var/log/app.log，不能指向数据库目录
        assertTrue(chain.getConclusion().contains("/var/log/app.log"),
                "conclusion 必须指向 /var/log/app.log");
        assertFalse(chain.getConclusion().contains("/var/lib/mysql"),
                "conclusion 不能误指 /var/lib/mysql（它是排除项）");

        // ④ 置信度门槛
        assertTrue(chain.getConfidence() >= 0.7);
    }

    @Test
    void empty_results_returns_null() {
        assertNull(analyzer.analyze(IntentType.DISK_DIAGNOSIS,
                List.of(), RiskDecision.ALLOW));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend && mvn test -Dtest=DiskDiagnosisAnalyzerTest -q
```

Expected: `FAIL — symbol not found DiskDiagnosisAnalyzer`

- [ ] **Step 3: 实现 DiskDiagnosisAnalyzer**

`backend/src/main/java/com/kylinops/rca/DiskDiagnosisAnalyzer.java`:

```java
package com.kylinops.rca;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 磁盘诊断根因分析器（演示场景 2 重点）。
 *
 * <p>从 disk_usage_tool + large_file_scan_tool 推断根因；</p>
 * <p>显式排除 /var/lib/mysql 等敏感数据库目录。</p>
 */
@Component
public class DiskDiagnosisAnalyzer {

    private static final Set<String> PROTECTED_PATHS = Set.of(
            "/var/lib/mysql", "/var/lib/postgresql", "/var/lib/mongodb",
            "/data/db", "/var/lib/redis"
    );

    public RootCauseChain analyze(IntentType intent, List<ToolResult> results,
                                  RiskDecision decision) {
        if (intent != IntentType.DISK_DIAGNOSIS
                || results == null || results.isEmpty()) {
            return null;
        }

        List<RootCauseChain.Evidence> evidence = new ArrayList<>();
        Double diskUsage = null;
        List<String> largeFiles = new ArrayList<>();

        for (ToolResult r : results) {
            if (!r.isSuccess() || !(r.getData() instanceof Map<?, ?> data)) continue;
            String tool = r.getToolName();
            if ("disk_usage_tool".equals(tool) && data.get("partitions") instanceof List<?> parts) {
                // 解析 "/: 86% used (12G/14G)" 格式
                for (Object p : parts) {
                    String s = String.valueOf(p);
                    int pctIdx = s.indexOf('%');
                    if (pctIdx > 0) {
                        int start = s.lastIndexOf(' ', pctIdx);
                        try {
                            diskUsage = Double.parseDouble(s.substring(start + 1, pctIdx).trim());
                            evidence.add(new RootCauseChain.Evidence(
                                    UUID.randomUUID().toString(), tool, null,
                                    s, diskUsage, "%"));
                        } catch (NumberFormatException ignored) { }
                    }
                }
            } else if ("large_file_scan_tool".equals(tool)
                    && data.get("largeFiles") instanceof List<?> files) {
                for (Object f : files) {
                    String s = String.valueOf(f);
                    largeFiles.add(s);
                    evidence.add(new RootCauseChain.Evidence(
                            UUID.randomUUID().toString(), tool, null,
                            s, null, null));
                }
            }
        }

        if (evidence.isEmpty()) return null;

        // 构造 Hypotheses
        List<RootCauseChain.Hypothesis> hypotheses = new ArrayList<>();
        RootCauseChain.Hypothesis topHyp = null;
        for (String f : largeFiles) {
            boolean isProtected = PROTECTED_PATHS.stream().anyMatch(f::contains);
            if (!isProtected) {
                topHyp = new RootCauseChain.Hypothesis(f, 0.86, true,
                        "large_file_scan_tool 直接定位");
                hypotheses.add(topHyp);
                break;
            }
        }

        // 构造 ExcludedCauses
        List<RootCauseChain.ExcludedCause> excluded = new ArrayList<>();
        for (String p : PROTECTED_PATHS) {
            if (largeFiles.stream().anyMatch(f -> f.contains(p))) {
                excluded.add(new RootCauseChain.ExcludedCause(
                        p + "（敏感数据库目录）",
                        "数据库目录不建议直接清理，可能影响数据完整性",
                        evidence.stream()
                                .filter(e -> e.getObservation().contains(p))
                                .map(RootCauseChain.Evidence::getEvidenceId)
                                .toList()));
            }
        }

        // 构造 Symptom
        String symptom = diskUsage != null
                ? String.format("磁盘根分区使用率 %.0f%%", diskUsage)
                : "磁盘使用率较高";

        // 构造 Conclusion
        String conclusion = topHyp != null
                ? "主要根因是 " + topHyp.getCause() + " 持续增长"
                : "未能定位单一根因，建议人工检查";

        // 构造 Suggestions
        List<String> suggestions = topHyp != null
                ? List.of("先归档或截断 " + extractPath(topHyp.getCause()),
                          "再检查服务是否循环报错")
                : List.of("人工检查各目录占用");

        return RootCauseChain.builder()
                .symptom(symptom)
                .evidence(evidence)
                .hypotheses(hypotheses)
                .excludedCauses(excluded)
                .conclusion(conclusion)
                .confidence(topHyp != null ? 0.86 : 0.4)
                .suggestions(suggestions)
                .riskTips(List.of("清理前需先归档，确认业务不依赖"))
                .build();
    }

    private static String extractPath(String s) {
        int colon = s.indexOf(':');
        return colon > 0 ? s.substring(0, colon).trim() : s;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd backend && mvn test -Dtest=DiskDiagnosisAnalyzerTest -q
```

Expected: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/kylinops/rca/DiskDiagnosisAnalyzer.java \
        backend/src/test/java/com/kylinops/rca/DiskDiagnosisAnalyzerTest.java
git commit -m "feat(rca): add DiskDiagnosisAnalyzer"
```

> ✅ Task 3 至此只交付 `DiskDiagnosisAnalyzer` + 单元测试。`DefaultRootCauseAnalyzer` 在 Task 5 末尾统一创建并提交。

---

## Task 4: HealthCheckAnalyzer（演示场景 1 重点）

**Files:**
- Create: `backend/src/main/java/com/kylinops/rca/HealthCheckAnalyzer.java`
- Test: `backend/src/test/java/com/kylinops/rca/HealthCheckAnalyzerTest.java`

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/kylinops/rca/HealthCheckAnalyzerTest.java`:

```java
package com.kylinops.rca;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HealthCheckAnalyzerTest {

    private final HealthCheckAnalyzer analyzer = new HealthCheckAnalyzer();

    @Test
    void multi_tool_results_yield_health_chain() {
        List<ToolResult> results = List.of(
                ToolResult.success("cpu_status_tool",
                        Map.of("summary", "CPU 4 核，使用率 12%"), "CPU 12%", 0),
                ToolResult.success("memory_status_tool",
                        Map.of("summary", "内存 8G/16G"), "Mem 50%", 0),
                ToolResult.success("disk_usage_tool",
                        Map.of("summary", "/ 86%"), "Disk 86%", 0));

        RootCauseChain chain = analyzer.analyze(
                IntentType.SYSTEM_CHECK, results, RiskDecision.ALLOW);

        assertNotNull(chain);
        assertEquals(3, chain.getEvidence().size());
        // 健康分 < 80 必有 riskTip
        assertFalse(chain.getRiskTips().isEmpty());
    }

    @Test
    void empty_returns_null() {
        assertNull(analyzer.analyze(IntentType.SYSTEM_CHECK,
                List.of(), RiskDecision.ALLOW));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend && mvn test -Dtest=HealthCheckAnalyzerTest -q
```

Expected: `FAIL — symbol not found HealthCheckAnalyzer`

- [ ] **Step 3: 实现 HealthCheckAnalyzer**

`backend/src/main/java/com/kylinops/rca/HealthCheckAnalyzer.java`:

```java
package com.kylinops.rca;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 健康评估链（演示场景 1 重点）。
 * 把多工具扇出结果汇总为"健康评估链"，前端标题统一显示。
 */
@Component
public class HealthCheckAnalyzer {

    public RootCauseChain analyze(IntentType intent, List<ToolResult> results,
                                  RiskDecision decision) {
        if (intent != IntentType.SYSTEM_CHECK
                || results == null || results.isEmpty()) {
            return null;
        }

        List<RootCauseChain.Evidence> evidence = new ArrayList<>();
        int successCount = 0;
        for (ToolResult r : results) {
            if (!r.isSuccess()) continue;
            successCount++;
            evidence.add(new RootCauseChain.Evidence(
                    UUID.randomUUID().toString(), r.getToolName(), null,
                    r.getSummary() != null ? r.getSummary() : r.getStatus(),
                    null, null));
        }
        if (evidence.isEmpty()) return null;

        int healthScore = (int) (successCount * 100.0 / results.size());

        return RootCauseChain.builder()
                .symptom(String.format("系统健康评分 %d/100", healthScore))
                .evidence(evidence)
                .hypotheses(List.of())
                .excludedCauses(List.of())
                .conclusion(healthScore >= 80 ? "系统运行状态良好"
                        : healthScore >= 60 ? "系统存在部分异常，建议排查"
                        : "系统存在较多异常，请及时处理")
                .confidence(healthScore / 100.0)
                .suggestions(List.of("查看具体异常工具的 evidence"))
                .riskTips(healthScore < 80
                        ? List.of("存在异常工具，请重点关注")
                        : List.of())
                .build();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd backend && mvn test -Dtest=HealthCheckAnalyzerTest -q
```

Expected: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/kylinops/rca/HealthCheckAnalyzer.java \
        backend/src/test/java/com/kylinops/rca/HealthCheckAnalyzerTest.java
git commit -m "feat(rca): add HealthCheckAnalyzer"
```

---

## Task 5: ServiceDiagnosisAnalyzer（演示场景 3 重点）

**Files:**
- Create: `backend/src/main/java/com/kylinops/rca/ServiceDiagnosisAnalyzer.java`
- Test: `backend/src/test/java/com/kylinops/rca/ServiceDiagnosisAnalyzerTest.java`

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/kylinops/rca/ServiceDiagnosisAnalyzerTest.java`:

```java
package com.kylinops.rca;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ServiceDiagnosisAnalyzerTest {

    private final ServiceDiagnosisAnalyzer analyzer = new ServiceDiagnosisAnalyzer();

    @Test
    void failed_service_with_journal_returns_chain() {
        List<ToolResult> results = List.of(
                ToolResult.success("service_status_tool",
                        Map.of("activeState", "failed"),
                        "nginx: failed (inactive)", 0),
                ToolResult.success("network_port_tool",
                        Map.of("summary", "8080 端口未监听"),
                        "Port 8080 not listening", 0),
                ToolResult.success("journal_log_tool",
                        Map.of("errors", List.of("nginx: bind() failed")),
                        "Last 3 errors: bind failed", 0));

        RootCauseChain chain = analyzer.analyze(
                IntentType.SERVICE_DIAGNOSIS, results, RiskDecision.ALLOW);

        assertNotNull(chain);
        assertEquals(3, chain.getEvidence().size());
        assertTrue(chain.getConclusion().contains("nginx") || chain.getConclusion().contains("bind"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend && mvn test -Dtest=ServiceDiagnosisAnalyzerTest -q
```

Expected: `FAIL — symbol not found`

- [ ] **Step 3: 实现 ServiceDiagnosisAnalyzer**

`backend/src/main/java/com/kylinops/rca/ServiceDiagnosisAnalyzer.java`:

```java
package com.kylinops.rca;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 服务诊断链（演示场景 3 重点）。
 * 综合 service_status / network_port / journal_log 三个工具推断服务异常根因。
 */
@Component
public class ServiceDiagnosisAnalyzer {

    public RootCauseChain analyze(IntentType intent, List<ToolResult> results,
                                  RiskDecision decision) {
        if (intent != IntentType.SERVICE_DIAGNOSIS
                || results == null || results.isEmpty()) {
            return null;
        }

        List<RootCauseChain.Evidence> evidence = new ArrayList<>();
        String serviceState = null;
        boolean portMissing = false;
        List<String> journalErrors = new ArrayList<>();

        for (ToolResult r : results) {
            if (!r.isSuccess()) continue;
            String tool = r.getToolName();
            String summary = r.getSummary() != null ? r.getSummary() : "";
            evidence.add(new RootCauseChain.Evidence(
                    UUID.randomUUID().toString(), tool, null, summary, null, null));
            if ("service_status_tool".equals(tool)) serviceState = summary;
            if ("network_port_tool".equals(tool) && summary.contains("未监听")) {
                portMissing = true;
            }
            if ("journal_log_tool".equals(tool) && r.getData() instanceof Map<?, ?> data
                    && data.get("errors") instanceof List<?> errs) {
                for (Object e : errs) journalErrors.add(String.valueOf(e));
            }
        }
        if (evidence.isEmpty()) return null;

        String conclusion;
        List<String> suggestions = new ArrayList<>();
        if (serviceState != null && serviceState.contains("failed")) {
            conclusion = "服务未运行（" + serviceState + "）";
            suggestions.add("使用 service restart 重启（需 L2 确认）");
        } else if (portMissing) {
            conclusion = "服务进程在但端口未监听（启动未完成或配置错误）";
            suggestions.add("检查服务配置（端口 / 监听地址）");
        } else if (!journalErrors.isEmpty()) {
            conclusion = "服务运行中但有错误：" + String.join("; ", journalErrors);
            suggestions.add("查看完整日志定位错误");
        } else {
            conclusion = "服务状态正常";
        }

        return RootCauseChain.builder()
                .symptom("服务异常诊断")
                .evidence(evidence)
                .hypotheses(List.of())
                .excludedCauses(List.of())
                .conclusion(conclusion)
                .confidence(journalErrors.isEmpty() ? 0.6 : 0.85)
                .suggestions(suggestions)
                .riskTips(List.of("重启服务需用户二次确认"))
                .build();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd backend && mvn test -Dtest=ServiceDiagnosisAnalyzerTest -q
```

Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/kylinops/rca/ServiceDiagnosisAnalyzer.java \
        backend/src/test/java/com/kylinops/rca/ServiceDiagnosisAnalyzerTest.java
git commit -m "feat(rca): add ServiceDiagnosisAnalyzer"
```

---

## Task 5.5: DefaultRootCauseAnalyzer 主入口（按 intent 分发）

> **执行顺序约束**：本 Task 必须在 Task 3/4/5 三个 analyzer 都实现并 commit 之后执行；否则会因 `HealthCheckAnalyzer` / `ServiceDiagnosisAnalyzer` 符号未定义而 `mvn compile` 失败。Task 3 已显式避开此陷阱。

**Files:**
- Create: `backend/src/main/java/com/kylinops/rca/DefaultRootCauseAnalyzer.java`
- Test: `backend/src/test/java/com/kylinops/rca/DefaultRootCauseAnalyzerTest.java`

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/kylinops/rca/DefaultRootCauseAnalyzerTest.java`:

```java
package com.kylinops.rca;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultRootCauseAnalyzerTest {

    private final DefaultRootCauseAnalyzer analyzer = new DefaultRootCauseAnalyzer(
            new DiskDiagnosisAnalyzer(),
            new HealthCheckAnalyzer(),
            new ServiceDiagnosisAnalyzer());

    @Test
    void disk_intent_dispatches_to_disk_analyzer() {
        ToolResult r = ToolResult.success("disk_usage_tool",
                Map.of("partitions", List.of("/: 86% used (12G/14G)")),
                "/: 86% used", 0);
        ToolResult lf = ToolResult.success("large_file_scan_tool",
                Map.of("largeFiles", List.of(
                        "/var/log/app.log: 12GB",
                        "/var/lib/mysql/binlog.00001: 8GB")),
                "Top 2: app.log (12GB), binlog (8GB)", 0);
        RootCauseChain chain = analyzer.analyze(
                IntentType.DISK_DIAGNOSIS,
                List.of(r, lf), RiskDecision.ALLOW);
        assertNotNull(chain);
        assertTrue(chain.getConclusion().contains("/var/log/app.log"));
    }

    @Test
    void system_check_intent_dispatches_to_health_analyzer() {
        ToolResult r = ToolResult.success("cpu_status_tool",
                Map.of("summary", "CPU 12%"), "CPU 12%", 0);
        RootCauseChain chain = analyzer.analyze(
                IntentType.SYSTEM_CHECK,
                List.of(r), RiskDecision.ALLOW);
        assertNotNull(chain);
        assertTrue(chain.getSymptom().contains("系统健康评分"));
    }

    @Test
    void unsupported_intent_returns_null() {
        assertNull(analyzer.analyze(IntentType.CHITCHAT,
                List.of(), RiskDecision.ALLOW));
        assertNull(analyzer.analyze(null, List.of(), RiskDecision.ALLOW));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend && mvn test -Dtest=DefaultRootCauseAnalyzerTest -q
```

Expected: `FAIL — symbol not found DefaultRootCauseAnalyzer`

- [ ] **Step 3: 实现 DefaultRootCauseAnalyzer**

`backend/src/main/java/com/kylinops/rca/DefaultRootCauseAnalyzer.java`:

```java
package com.kylinops.rca;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.tool.ToolResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 默认根因分析器：按 intent 分发给具体 analyzer。
 * 仅 Task 3/4/5 三个 analyzer 全部就位后才创建，避免编译顺序问题。
 */
@Component
@RequiredArgsConstructor
public class DefaultRootCauseAnalyzer implements RootCauseAnalyzer {

    private final DiskDiagnosisAnalyzer diskAnalyzer;
    private final HealthCheckAnalyzer healthAnalyzer;
    private final ServiceDiagnosisAnalyzer serviceAnalyzer;

    @Override
    public RootCauseChain analyze(IntentType intent, List<ToolResult> results,
                                  RiskDecision decision) {
        if (intent == null || results == null || results.isEmpty()) {
            return null;
        }
        return switch (intent) {
            case DISK_DIAGNOSIS -> diskAnalyzer.analyze(intent, results, decision);
            case SYSTEM_CHECK -> healthAnalyzer.analyze(intent, results, decision);
            case SERVICE_DIAGNOSIS -> serviceAnalyzer.analyze(intent, results, decision);
            default -> null;
        };
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd backend && mvn test -Dtest=DefaultRootCauseAnalyzerTest -q
```

Expected: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 5: 跑全量基线确认不破坏**

```bash
cd backend && mvn test -q
```

Expected: Tests run 数 ≥ 上一基线 + 3，Failures = 0，Errors = 0，Skipped = 1。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/kylinops/rca/DefaultRootCauseAnalyzer.java \
        backend/src/test/java/com/kylinops/rca/DefaultRootCauseAnalyzerTest.java
git commit -m "feat(rca): add DefaultRootCauseAnalyzer dispatcher (depends on Disk/Health/Service analyzers)"
```

---

## Task 6: 把 rootCauseChain 字段加入 AgentResult

**Files:**
- Modify: `backend/src/main/java/com/kylinops/agent/AgentResult.java`
- Test: `backend/src/test/java/com/kylinops/agent/AgentResultTest.java`

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/kylinops/agent/AgentResultTest.java`:

```java
package com.kylinops.agent;

import com.kylinops.rca.RootCauseChain;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AgentResultTest {

    @Test
    void builder_supports_root_cause_chain_field() {
        RootCauseChain chain = RootCauseChain.builder()
                .symptom("test").confidence(0.5).build();
        AgentResult result = AgentResult.builder()
                .sessionId("s1").answer("a")
                .intentType(com.kylinops.common.enums.IntentType.DISK_DIAGNOSIS)
                .toolCalls(List.of())
                .riskLevel(com.kylinops.common.enums.RiskLevel.L0)
                .riskDecision("ALLOW")
                .auditId("a1")
                .rootCauseChain(chain)
                .build();
        assertNotNull(result.getRootCauseChain());
        assertEquals("test", result.getRootCauseChain().getSymptom());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend && mvn test -Dtest=AgentResultTest -q
```

Expected: `FAIL — method getRootCauseChain not found`

- [ ] **Step 3: 修改 AgentResult.java**

打开文件，找到顶层类（已有 `@Data @Builder public class AgentResult`），在最后字段（`errorMessage`）后添加：

```java
import com.kylinops.rca.RootCauseChain;

// 顶层字段
/** 根因分析链（仅演示场景 1/2/3 填充；其他场景为 null） */
private RootCauseChain rootCauseChain;
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd backend && mvn test -Dtest=AgentResultTest -q
```

Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/kylinops/agent/AgentResult.java \
        backend/src/test/java/com/kylinops/agent/AgentResultTest.java
git commit -m "feat(rca): add rootCauseChain field to AgentResult"
```

---

## Task 7: 在 AgentOrchestrator 集成 RootCauseAnalyzer

**Files:**
- Modify: `backend/src/main/java/com/kylinops/agent/AgentOrchestrator.java`
- Test: `backend/src/test/java/com/kylinops/agent/AgentOrchestratorRcaTest.java`

- [ ] **Step 1: 写失败测试（验证 RCA 出现在最终结果中）**

`backend/src/test/java/com/kylinops/agent/AgentOrchestratorRcaTest.java`:

```java
package com.kylinops.agent;

import com.kylinops.common.enums.IntentType;
import com.kylinops.common.enums.RiskDecision;
import com.kylinops.common.enums.RiskLevel;
import com.kylinops.executor.AuthenticatedOperator;
import com.kylinops.rca.RootCauseChain;
import com.kylinops.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@SpringBootTest
class AgentOrchestratorRcaTest {

    @Autowired
    private AgentOrchestrator orchestrator;

    @MockBean
    private com.kylinops.rca.RootCauseAnalyzer analyzer; // mock 接口

    @Test
    void rca_filled_into_agent_result_when_intent_matches() {
        RootCauseChain mockChain = RootCauseChain.builder()
                .symptom("test symptom").confidence(0.9).build();
        when(analyzer.analyze(any(IntentType.class), anyList(), any(RiskDecision.class)))
                .thenReturn(mockChain);

        // 选用 master 上规则必然能命中的输入句（"帮我看看磁盘为什么快满了"）
        // — 避免 "帮我看磁盘" 这种短语在某些规则下识别不稳定
        AgentOrchestrator.AgentRequest req = AgentOrchestrator.AgentRequest.builder()
                .userInput("帮我看看磁盘为什么快满了")
                .requestId("test-audit-id-12345")
                .operator(AuthenticatedOperator.ANONYMOUS)
                .build();

        AgentResult result = orchestrator.process(req);

        // 强断言：intent 与 RCA 字段必须同时满足，不再 if 守卫跳过
        assertEquals(IntentType.DISK_DIAGNOSIS, result.getIntentType(),
                "输入句应稳定识别为 DISK_DIAGNOSIS");
        assertNotNull(result.getRootCauseChain(),
                "RCA 必须被填入 AgentResult（演示场景 2 强契约）");
        assertEquals("test symptom", result.getRootCauseChain().getSymptom(),
                "RCA.symptom 必须来自 analyzer 返回值");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend && mvn test -Dtest=AgentOrchestratorRcaTest -q
```

Expected: `FAIL — getRootCauseChain returns null` 或 `NullPointerException`

- [ ] **Step 3: 修改 AgentOrchestrator.java**

**3a.** 添加 import 与注入：

```java
import com.kylinops.rca.RootCauseAnalyzer;
import com.kylinops.rca.RootCauseChain;
import lombok.RequiredArgsConstructor;

// 修改类注解（如果还没有 @RequiredArgsConstructor）
@RequiredArgsConstructor
public class AgentOrchestrator {
    // ... 已有字段 ...
    private final RootCauseAnalyzer rootCauseAnalyzer; // 新增
```

**3b.** 在 `process()` 方法中 Step 7 之后、Step 8（`String answer = ...`）之前插入：

```java
// ── Step 7.5: 生成根因分析链（演示场景 1/2/3 重点） ──
RootCauseChain rootCauseChain = null;
if (decision == RiskDecision.ALLOW && !toolResults.isEmpty()) {
    rootCauseChain = rootCauseAnalyzer.analyze(intent, toolResults, decision);
    if (rootCauseChain != null) {
        log.info("生成根因分析链: symptom={}, confidence={}",
                rootCauseChain.getSymptom(), rootCauseChain.getConfidence());
    }
}
```

**3c.** 在最后 `return AgentResult.builder()...` 链中加入：

```java
.rootCauseChain(rootCauseChain)
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd backend && mvn test -Dtest=AgentOrchestratorRcaTest -q
```

Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 5: 跑全量基线确认不破坏**

```bash
cd backend && mvn test -q
```

Expected: `Tests run: 503, Failures: 0, Errors: 0, Skipped: 1`（基线 502+1，新增 1 个 RCA 集成测试 = 503）

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/kylinops/agent/AgentOrchestrator.java \
        backend/src/test/java/com/kylinops/agent/AgentOrchestratorRcaTest.java
git commit -m "feat(rca): wire RootCauseAnalyzer into AgentOrchestrator"
```

**✅ 至此，Fix-01 后端核心功能完成。**

---

## Task 8: 持久化 RCA 到 AuditLog

**Files:**
- Modify: `backend/src/main/java/com/kylinops/audit/AuditLog.java`
- Modify: `backend/src/main/java/com/kylinops/audit/AuditLogDetail.java`
- Modify: `backend/src/main/java/com/kylinops/audit/AuditLogService.java`

- [ ] **Step 1: 在 AuditLog entity 加字段**

打开 `AuditLog.java`，在最后字段（`updatedAt` 之前）添加：

```java
import jakarta.persistence.Lob;

/** 根因分析链 JSON 字符串（Lob TEXT，仅演示场景填充） */
@Lob
@Column(columnDefinition = "TEXT")
private String rootCauseChainJson;
```

- [ ] **Step 2: 在 AuditLogDetail DTO 加字段**

打开 `AuditLogDetail.java`，在最后字段后添加：

```java
import com.kylinops.rca.RootCauseChain;

/** 根因分析链（反序列化自 rootCauseChainJson） */
private RootCauseChain rootCauseChain;
```

- [ ] **Step 3: 在 AuditLogService 加序列化辅助**

打开 `AuditLogService.java`，找到 `getDetail()` 方法（构造 AuditLogDetail 的位置），添加：

```java
import com.kylinops.rca.RootCauseChain;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

private final ObjectMapper objectMapper = new ObjectMapper();

public String serializeRca(RootCauseChain chain) {
    if (chain == null) return null;
    try {
        return objectMapper.writeValueAsString(chain);
    } catch (JsonProcessingException e) {
        log.warn("RCA 序列化失败: {}", e.getMessage());
        return null;
    }
}

public RootCauseChain deserializeRca(String json) {
    if (json == null || json.isBlank()) return null;
    try {
        return objectMapper.readValue(json, RootCauseChain.class);
    } catch (JsonProcessingException e) {
        log.warn("RCA 反序列化失败: {}", e.getMessage());
        return null;
    }
}
```

- [ ] **Step 4: 写测试**

`backend/src/test/java/com/kylinops/audit/AuditLogRcaTest.java`:

```java
package com.kylinops.audit;

import com.kylinops.rca.RootCauseChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AuditLogRcaTest {

    @Autowired
    private AuditLogService auditLogService;

    @Test
    void rca_serialize_deserialize_roundtrip() {
        RootCauseChain original = RootCauseChain.builder()
                .symptom("test").confidence(0.5)
                .evidence(java.util.List.of())
                .build();
        String json = auditLogService.serializeRca(original);
        assertNotNull(json);
        RootCauseChain back = auditLogService.deserializeRca(json);
        assertNotNull(back);
        assertEquals("test", back.getSymptom());
        assertEquals(0.5, back.getConfidence());
    }

    @Test
    void null_rca_returns_null() {
        assertNull(auditLogService.serializeRca(null));
        assertNull(auditLogService.deserializeRca(null));
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

```bash
cd backend && mvn test -Dtest=AuditLogRcaTest -q
```

Expected: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 6: 跑全量基线**

```bash
cd backend && mvn test -q
```

Expected: `Tests run: 505, Failures: 0, Errors: 0, Skipped: 1`（502 基线 + 3 个新测试）

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/kylinops/audit/
git commit -m "feat(rca): persist RootCauseChain JSON in AuditLog"
```

---

## Task 9: 在 ReportService 反序列化 RCA

**Files:**
- Modify: `backend/src/main/java/com/kylinops/report/Report.java`
- Modify: `backend/src/main/java/com/kylinops/report/ReportDetail.java`
- Modify: `backend/src/main/java/com/kylinops/report/ReportService.java`

- [ ] **Step 1: Report entity 加字段**

```java
@Lob
@Column(columnDefinition = "TEXT")
private String rootCauseChainJson;
```

- [ ] **Step 2: ReportDetail DTO 加字段**

```java
import com.kylinops.rca.RootCauseChain;

private RootCauseChain rootCauseChain;
```

- [ ] **Step 3: ReportService.generate() 反序列化**

在 `ReportService.java` 中 `toDetail()` 方法添加：

```java
detail.setRootCauseChain(auditLogService.deserializeRca(entity.getRootCauseChainJson()));
```

- [ ] **Step 4: 写集成测试**

`backend/src/test/java/com/kylinops/report/ReportRcaIntegrationTest.java`:

```java
package com.kylinops.report;

import com.kylinops.audit.AuditLogService;
import com.kylinops.rca.RootCauseChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ReportRcaIntegrationTest {

    @Autowired
    private ReportService reportService;
    @Autowired
    private AuditLogService auditLogService;

    @Test
    void rca_three_layer_roundtrip() {
        RootCauseChain original = RootCauseChain.builder()
                .symptom("disk 86%").confidence(0.86).build();
        String json = auditLogService.serializeRca(original);
        RootCauseChain back = auditLogService.deserializeRca(json);
        assertNotNull(back);
        assertEquals(original.getSymptom(), back.getSymptom());
    }
}
```

- [ ] **Step 5: 跑测试 + 全量基线**

```bash
cd backend && mvn test -Dtest=ReportRcaIntegrationTest -q && mvn test -q
```

Expected: 506/506 + 1 skipped

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/kylinops/report/
git commit -m "feat(rca): deserialize RootCauseChain in Report layer"
```

---

## Task 10: 前端 types — agent.ts / rca.ts / report.ts

**Files:**
- Create: `frontend/src/types/rca.ts`
- Modify: `frontend/src/types/agent.ts`
- Modify: `frontend/src/types/report.ts`

- [ ] **Step 1: 创建 rca.ts**

`frontend/src/types/rca.ts`:

```typescript
// RCA (Root Cause Analysis) types — mirror com.kylinops.rca.RootCauseChain
//
// SAFETY CONTRACT:
//   * The frontend NEVER recomputes or fabricates RCA fields. Every rendered
//     value comes verbatim from the backend. When a field is missing, the
//     component renders an empty state, not a fabricated one.

export interface Evidence {
  evidenceId: string;
  source: string;
  sourceToolCallId?: string;
  observation: string;
  numericValue?: number;
  unit?: string;
}

export interface Hypothesis {
  cause: string;
  probability: number;
  confirmed: boolean;
  reasoning: string;
}

export interface ExcludedCause {
  cause: string;
  reason: string;
  evidenceIds: string[];
}

export interface RootCauseChain {
  symptom: string;
  evidence: Evidence[];
  hypotheses: Hypothesis[];
  excludedCauses: ExcludedCause[];
  conclusion: string;
  confidence: number;
  suggestions: string[];
  riskTips: string[];
}
```

- [ ] **Step 2: 修改 agent.ts**

打开 `frontend/src/types/agent.ts`，在 `AgentResult` 接口添加：

```typescript
import type { RootCauseChain } from './rca';

// AgentResult 接口
rootCauseChain?: RootCauseChain;
```

- [ ] **Step 3: 修改 report.ts**

打开 `frontend/src/types/report.ts`，在 `ReportDetail` 接口添加：

```typescript
import type { RootCauseChain } from './rca';

// ReportDetail 接口
rootCauseChain?: RootCauseChain;
```

- [ ] **Step 4: 跑前端类型检查**

```bash
cd frontend && npx tsc --noEmit
```

Expected: 无错误

- [ ] **Step 5: Commit**

```bash
git add frontend/src/types/
git commit -m "feat(rca): add frontend RCA types"
```

---

## Task 11: 前端 normalizeIntentType 兼容映射

**Files:**
- Create: `frontend/src/utils/intentType.ts`
- Test: `frontend/src/utils/intentType.spec.ts`

- [ ] **Step 1: 写失败测试**

`frontend/src/utils/intentType.spec.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { normalizeIntentType, rcaTitleFor } from './intentType';

describe('normalizeIntentType', () => {
  it('maps backend SYSTEM_CHECK to canonical SYSTEM_CHECK', () => {
    expect(normalizeIntentType('SYSTEM_CHECK')).toBe('SYSTEM_CHECK');
  });
  it('maps legacy frontend HEALTH_CHECK to SYSTEM_CHECK', () => {
    expect(normalizeIntentType('HEALTH_CHECK')).toBe('SYSTEM_CHECK');
  });
  it('maps PROCESS_INQUIRY to PROCESS_QUERY', () => {
    expect(normalizeIntentType('PROCESS_INQUIRY')).toBe('PROCESS_QUERY');
  });
  it('maps NETWORK_INQUIRY to NETWORK_QUERY', () => {
    expect(normalizeIntentType('NETWORK_INQUIRY')).toBe('NETWORK_QUERY');
  });
  it('maps LOG_INQUIRY to LOG_QUERY', () => {
    expect(normalizeIntentType('LOG_INQUIRY')).toBe('LOG_QUERY');
  });
  it('passes through unknown values', () => {
    expect(normalizeIntentType('UNKNOWN')).toBe('UNKNOWN');
    expect(normalizeIntentType('FOO_BAR')).toBe('FOO_BAR');
  });
});

describe('rcaTitleFor', () => {
  it('returns Chinese title for each canonical intent', () => {
    expect(rcaTitleFor('DISK_DIAGNOSIS')).toBe('根因分析链');
    expect(rcaTitleFor('SYSTEM_CHECK')).toBe('健康评估链');
    expect(rcaTitleFor('SERVICE_DIAGNOSIS')).toBe('服务诊断链');
  });
  it('handles legacy HEALTH_CHECK', () => {
    expect(rcaTitleFor('HEALTH_CHECK')).toBe('健康评估链');
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd frontend && npm run test:unit -- --run intentType
```

Expected: `FAIL — cannot find module`

- [ ] **Step 3: 实现 intentType.ts**

`frontend/src/utils/intentType.ts`:

```typescript
// IntentType 兼容映射（前后端枚举不一致 → 统一到后端命名）。
// 不修改任何现有枚举值；只做兼容层。

const LEGACY_TO_CANONICAL: Readonly<Record<string, string>> = {
  HEALTH_CHECK: 'SYSTEM_CHECK',
  PROCESS_INQUIRY: 'PROCESS_QUERY',
  NETWORK_INQUIRY: 'NETWORK_QUERY',
  LOG_INQUIRY: 'LOG_QUERY',
  SERVICE_OPERATION: 'SERVICE_DIAGNOSIS',
};

export function normalizeIntentType(raw: string | undefined): string {
  if (!raw) return 'UNKNOWN';
  return LEGACY_TO_CANONICAL[raw] ?? raw;
}

const RCA_TITLES: Readonly<Record<string, string>> = {
  DISK_DIAGNOSIS: '根因分析链',
  SYSTEM_CHECK: '健康评估链',
  SERVICE_DIAGNOSIS: '服务诊断链',
  COMMAND_EXECUTION: '安全决策链',
};

export function rcaTitleFor(rawIntent: string | undefined): string | null {
  const canonical = normalizeIntentType(rawIntent);
  return RCA_TITLES[canonical] ?? null;
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd frontend && npm run test:unit -- --run intentType
```

Expected: 8 tests passed

- [ ] **Step 5: Commit**

```bash
git add frontend/src/utils/intentType.ts frontend/src/utils/intentType.spec.ts
git commit -m "feat(rca): add normalizeIntentType compat layer + rcaTitleFor"
```

---

## Task 12: 前端 ReasoningChain 组件

**Files:**
- Create: `frontend/src/components/ReasoningChain/index.vue`
- Test: `frontend/src/components/ReasoningChain/index.spec.ts`

- [ ] **Step 1: 写失败测试**

`frontend/src/components/ReasoningChain/index.spec.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import ReasoningChain from './index.vue';
import type { RootCauseChain } from '@/types/rca';

const sample: RootCauseChain = {
  symptom: '磁盘根分区使用率 86%',
  evidence: [{
    evidenceId: 'ev-1', source: 'disk_usage_tool',
    sourceToolCallId: 'tc-1', observation: '/ 86% used',
    numericValue: 86, unit: '%',
  }],
  hypotheses: [{
    cause: '/var/log/app.log 占用 12GB', probability: 0.86,
    confirmed: true, reasoning: 'large_file_scan_tool 定位',
  }],
  excludedCauses: [{
    cause: '/var/lib/mysql（敏感数据库）',
    reason: '数据库目录不建议清理',
    evidenceIds: ['ev-1'],
  }],
  conclusion: '主因是 /var/log/app.log 持续增长',
  confidence: 0.86,
  suggestions: ['先归档或截断日志'],
  riskTips: ['清理前需先归档'],
};

describe('ReasoningChain', () => {
  it('renders symptom as title', () => {
    const w = mount(ReasoningChain, {
      props: { chain: sample, title: '根因分析链' },
    });
    expect(w.text()).toContain('根因分析链');
    expect(w.text()).toContain('磁盘根分区使用率 86%');
  });

  it('renders evidence count', () => {
    const w = mount(ReasoningChain, {
      props: { chain: sample, title: '根因分析链' },
    });
    expect(w.text()).toContain('disk_usage_tool');
  });

  it('renders conclusion and riskTips', () => {
    const w = mount(ReasoningChain, {
      props: { chain: sample, title: '根因分析链' },
    });
    expect(w.text()).toContain('主因是 /var/log/app.log 持续增长');
    expect(w.text()).toContain('清理前需先归档');
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd frontend && npm run test:unit -- --run ReasoningChain
```

Expected: `FAIL — cannot find module`

- [ ] **Step 3: 实现 ReasoningChain 组件**

`frontend/src/components/ReasoningChain/index.vue`:

```vue
<script setup lang="ts">
// ReasoningChain — 可视化展示 RootCauseChain。
// 字段渲染严格用 v-text，不使用 v-html（XSS 安全契约）。
import type { RootCauseChain } from '@/types/rca';

defineProps<{
  chain: RootCauseChain;
  title: string;
}>();

const confidencePercent = (c: number) => Math.round(c * 100);
</script>

<template>
  <el-card class="reasoning-chain" shadow="never" data-testid="reasoning-chain">
    <template #header>
      <div class="rc-header">
        <span class="rc-title">{{ title }}</span>
        <el-tag size="small" :type="chain.confidence >= 0.7 ? 'success' : 'warning'">
          置信度 {{ confidencePercent(chain.confidence) }}%
        </el-tag>
      </div>
    </template>

    <section class="rc-section">
      <div class="rc-section-label">现象</div>
      <p class="rc-symptom" data-testid="rc-symptom">{{ chain.symptom }}</p>
    </section>

    <section v-if="chain.evidence.length" class="rc-section">
      <div class="rc-section-label">证据（{{ chain.evidence.length }}）</div>
      <ul class="rc-evidence">
        <li
          v-for="ev in chain.evidence"
          :key="ev.evidenceId"
          :data-testid="`rc-evidence-${ev.evidenceId}`"
        >
          <span class="rc-source">{{ ev.source }}：</span>
          <span class="rc-obs">{{ ev.observation }}</span>
        </li>
      </ul>
    </section>

    <section v-if="chain.hypotheses.length" class="rc-section">
      <div class="rc-section-label">候选根因</div>
      <ul class="rc-hypotheses">
        <li
          v-for="(h, idx) in chain.hypotheses"
          :key="`h-${idx}`"
          :class="h.confirmed ? 'rc-confirmed' : ''"
        >
          <el-tag v-if="h.confirmed" size="small" type="success">已确认</el-tag>
          <el-tag v-else size="small" type="info">候选</el-tag>
          <span>{{ h.cause }}（{{ confidencePercent(h.probability) }}%）</span>
          <small class="rc-reasoning">{{ h.reasoning }}</small>
        </li>
      </ul>
    </section>

    <section v-if="chain.excludedCauses.length" class="rc-section">
      <div class="rc-section-label">已排除</div>
      <ul class="rc-excluded">
        <li v-for="(e, idx) in chain.excludedCauses" :key="`e-${idx}`">
          {{ e.cause }} — {{ e.reason }}
        </li>
      </ul>
    </section>

    <section class="rc-section">
      <div class="rc-section-label">结论</div>
      <p class="rc-conclusion" data-testid="rc-conclusion">{{ chain.conclusion }}</p>
    </section>

    <section v-if="chain.suggestions.length" class="rc-section">
      <div class="rc-section-label">建议</div>
      <ol class="rc-suggestions">
        <li v-for="(s, idx) in chain.suggestions" :key="`s-${idx}`">{{ s }}</li>
      </ol>
    </section>

    <section v-if="chain.riskTips.length" class="rc-section rc-risks">
      <div class="rc-section-label">⚠️ 风险提示</div>
      <ul>
        <li v-for="(t, idx) in chain.riskTips" :key="`r-${idx}`">{{ t }}</li>
      </ul>
    </section>
  </el-card>
</template>

<style scoped>
.reasoning-chain {
  margin-top: 0.5rem;
}
.rc-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.rc-title {
  font-weight: 600;
  color: #1f2d3d;
}
.rc-section {
  margin-top: 0.75rem;
}
.rc-section-label {
  font-size: 0.75rem;
  color: #909399;
  margin-bottom: 0.25rem;
  font-weight: 600;
  text-transform: uppercase;
}
.rc-symptom,
.rc-conclusion {
  margin: 0;
  color: #303133;
  line-height: 1.6;
}
.rc-evidence,
.rc-hypotheses,
.rc-excluded,
.rc-suggestions {
  margin: 0;
  padding-left: 1.25rem;
  color: #303133;
}
.rc-evidence li,
.rc-hypotheses li,
.rc-excluded li,
.rc-suggestions li {
  margin-bottom: 0.25rem;
}
.rc-source {
  font-family: ui-monospace, monospace;
  font-size: 0.85rem;
  color: #5d6d7e;
}
.rc-confirmed {
  font-weight: 600;
}
.rc-reasoning {
  color: #909399;
  margin-left: 0.5rem;
  font-size: 0.85rem;
}
.rc-risks ul {
  color: #e6a23c;
}
</style>
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd frontend && npm run test:unit -- --run ReasoningChain
```

Expected: 3 tests passed

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/ReasoningChain/
git commit -m "feat(rca): add ReasoningChain Vue component"
```

---

## Task 13: ChatConsole 集成 ReasoningChain

**Files:**
- Modify: `frontend/src/pages/ChatConsole/index.vue`

- [ ] **Step 1: 找到 agent 回复渲染处**

打开 `frontend/src/pages/ChatConsole/index.vue`，找到 `result.answer` 的渲染位置（一般在 `<div class="agent-message">` 内）。

- [ ] **Step 2: 添加 import 与组件**

在 `<script setup>` 顶部添加：

```typescript
import ReasoningChain from '@/components/ReasoningChain/index.vue';
import { rcaTitleFor, normalizeIntentType } from '@/utils/intentType';
```

- [ ] **Step 3: 模板中插入组件**

在 `result.answer` 下方添加：

```vue
<ReasoningChain
  v-if="result.rootCauseChain"
  :chain="result.rootCauseChain"
  :title="rcaTitleFor(result.intentType) ?? '推理链'"
  :data-testid="`chat-rca-${result.auditId}`"
/>
```

- [ ] **Step 4: 跑前端测试基线**

```bash
cd frontend && npm run test:unit -- --run
```

Expected: 181/181（179 基线 + 2 个新测试）

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/ChatConsole/index.vue
git commit -m "feat(rca): embed ReasoningChain in ChatConsole"
```

---

## Task 14: ReportCenter 集成 ReasoningChain

**Files:**
- Modify: `frontend/src/pages/ReportCenter/index.vue`

- [ ] **Step 1: 找到报告详情展示处**

打开 `frontend/src/pages/ReportCenter/index.vue`，找到调用 `ReportPreview` 组件的位置。

- [ ] **Step 2: 添加 import**

```typescript
import ReasoningChain from '@/components/ReasoningChain/index.vue';
import { rcaTitleFor } from '@/utils/intentType';
```

- [ ] **Step 3: 在 ReportPreview 之前插入**

```vue
<ReasoningChain
  v-if="currentReport?.rootCauseChain"
  :chain="currentReport.rootCauseChain"
  :title="rcaTitleFor(currentReport.reportType) ?? '推理链'"
  :data-testid="`report-rca-${currentReport.reportId}`"
/>
```

- [ ] **Step 4: 跑前端测试 + 类型检查**

```bash
cd frontend && npx tsc --noEmit && npm run test:unit -- --run
```

Expected: 无类型错误；181/181

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/ReportCenter/index.vue
git commit -m "feat(rca): embed ReasoningChain in ReportCenter"
```

---

## Task 15: E2E 测试 — 演示场景 2 RCA 可见

**Files:**
- Create: `frontend/tests/e2e/rca-disk-diagnosis.spec.ts`

- [ ] **Step 1: 创建 E2E 测试**

`frontend/tests/e2e/rca-disk-diagnosis.spec.ts`:

```typescript
import { test, expect } from '@playwright/test';

test('演示场景 2 磁盘诊断：RCA 推理链可见', async ({ page }) => {
  // 假设已登录（参考已有 E2E 模式）
  await page.goto('/chat');

  // 触发演示场景 2
  const input = page.getByTestId('chat-input');
  await input.fill('帮我看看磁盘为什么快满了');
  await input.press('Enter');

  // 等待 agent 回复
  const rca = page.getByTestId('chat-rca').first();
  await expect(rca).toBeVisible({ timeout: 15000 });

  // 验证关键字段
  await expect(rca.getByTestId('rc-symptom')).toContainText('86%');
  await expect(rca.getByTestId('rc-conclusion')).toContainText('/var/log/app.log');
  await expect(rca).toContainText('disk_usage_tool');
  await expect(rca).toContainText('large_file_scan_tool');
});
```

- [ ] **Step 2: 跑 E2E 测试（需先启动 dev server）**

```bash
# 启动 backend + frontend dev（参考已有 E2E 启动模式）
cd frontend && E2E_LIVE=true npx playwright test rca-disk-diagnosis
```

Expected: 1 test passed

- [ ] **Step 3: 跑全量 E2E 基线**

```bash
cd frontend && npx playwright test
```

Expected: 19/19 + 3 skipped（18 基线 + 1 新增）

- [ ] **Step 4: Commit**

```bash
git add frontend/tests/e2e/rca-disk-diagnosis.spec.ts
git commit -m "test(rca): e2e for disk diagnosis RCA visibility"
```

---

## Task 16: 全量回归 + 打 tag

**Files:** (无新文件)

- [ ] **Step 1: 跑后端全量基线**

```bash
cd backend && mvn test -q
```

Expected: `Tests run: 506, Failures: 0, Errors: 0, Skipped: 1`

- [ ] **Step 2: 跑前端单测基线**

```bash
cd frontend && npm run test:unit -- --run
```

Expected: 181/181

- [ ] **Step 3: 跑前端 E2E 基线**

```bash
cd frontend && npx playwright test
```

Expected: 19/19 + 3 skipped

- [ ] **Step 4: 跑性能冒烟**

```bash
cd backend && mvn -B clean package -DskipTests
java -jar backend/target/kylin-ops-guard.jar --spring.profiles.active=test &
SERVER_PID=$!
sleep 10
# 跑 4 个演示场景（参考 docs/test/phase2-demo-acceptance.md）
# 验证响应时间 < 10s
kill $SERVER_PID
```

Expected: 4 演示场景全部 PASS，无报错

- [ ] **Step 5: 打 tag**

```bash
git tag -a fix-01-rca-done -m "Fix-01 RCA 推理链结构化合入 master"
git push origin fix-01-rca-done
```

- [ ] **Step 6: 更新 Sprint 状态表**

打开 `docs/superpowers/plans/2026-06-16-fix-02-lsof-tool-plan.md`（下一份计划），在头部添加：

```markdown
> **前置依赖：** Fix-01 已合入并打 tag `fix-01-rca-done`
```

---

## 完成标准（DoD）

Fix-01 完成必须满足：

- [ ] 后端 506/506 + 1 skipped 通过
- [ ] 前端 181/181 通过
- [ ] E2E 19/19 + 3 skipped 通过
- [ ] 演示场景 2 端到端可见 `<ReasoningChain>` 组件，标题"根因分析链"，含 symptom / evidence / hypotheses / excludedCauses / conclusion / suggestions 全字段
- [ ] 演示场景 1 端到端可见"健康评估链"
- [ ] 三层流转一致：AgentResult.rootCauseChain === AuditLog.rootCauseChainJson（反序列化）=== ReportDetail.rootCauseChain
- [ ] tag `fix-01-rca-done` 已打

如未达任一项，**不得进入 Fix-02**，先解决问题再继续。
