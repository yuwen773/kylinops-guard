# Phase 3 OpenAI-Compatible LLM Enhancement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add configurable DeepSeek/Qwen intent-parameter enhancement and ToolResult-grounded summaries while preserving deterministic planning, risk, confirmation, and fallback behavior.

**Architecture:** Introduce a small `LlmClient` around Spring RestClient, then place hybrid intent and response services behind interfaces consumed by AgentOrchestrator. All model outputs pass schema/allowlist validation; all tool contexts pass per-tool policies.

**Tech Stack:** Spring RestClient, Jackson, MockWebServer or WireMock, JUnit 5, Mockito, existing Agent/Tool abstractions.

---

## File Map

**Create**

- `backend/src/main/java/com/kylinops/llm/LlmClient.java`
- `backend/src/main/java/com/kylinops/llm/OpenAiCompatibleLlmClient.java`
- `backend/src/main/java/com/kylinops/llm/LlmClientException.java`
- `backend/src/main/java/com/kylinops/llm/LlmCallResult.java`
- `backend/src/main/java/com/kylinops/llm/LlmStage.java`
- `backend/src/main/java/com/kylinops/llm/OpenAiChatRequest.java`
- `backend/src/main/java/com/kylinops/llm/OpenAiChatResponse.java`
- `backend/src/main/java/com/kylinops/agent/intelligence/IntentResolution.java`
- `backend/src/main/java/com/kylinops/agent/intelligence/HybridIntentService.java`
- `backend/src/main/java/com/kylinops/agent/intelligence/LlmIntentParser.java`
- `backend/src/main/java/com/kylinops/agent/intelligence/HybridResponseService.java`
- `backend/src/main/java/com/kylinops/agent/intelligence/LlmContextSanitizer.java`
- `backend/src/main/java/com/kylinops/agent/intelligence/LlmToolContextPolicy.java`
- `backend/src/main/java/com/kylinops/agent/intelligence/LlmToolContextPolicyRegistry.java`
- `backend/src/main/java/com/kylinops/agent/intelligence/ResponseFactValidator.java`
- `backend/src/main/java/com/kylinops/audit/LlmCallRecord.java`
- `backend/src/main/java/com/kylinops/audit/LlmCallRecordRepository.java`
- `backend/src/main/resources/db/migration/V3__llm_call_audit.sql`
- `backend/src/test/java/com/kylinops/llm/OpenAiCompatibleLlmClientTest.java`
- `backend/src/test/java/com/kylinops/agent/intelligence/HybridIntentServiceTest.java`
- `backend/src/test/java/com/kylinops/agent/intelligence/HybridResponseServiceTest.java`
- `backend/src/test/java/com/kylinops/agent/intelligence/LlmToolContextPolicyRegistryTest.java`
- `backend/src/test/java/com/kylinops/agent/intelligence/IndirectPromptInjectionTest.java`
- `backend/src/test/java/com/kylinops/audit/LlmCallAuditTest.java`

**Modify**

- `backend/src/main/java/com/kylinops/config/KylinOpsConfig.java`
- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/application-prod.yml`
- `backend/src/main/java/com/kylinops/agent/AgentOrchestrator.java`
- `backend/src/main/java/com/kylinops/agent/AgentResponseBuilder.java`
- `backend/src/main/java/com/kylinops/agent/IntentClassifier.java`
- `backend/src/test/java/com/kylinops/agent/AgentOrchestratorSecurityTest.java`
- `backend/src/test/java/com/kylinops/agent/AgentOrchestratorIntegrationTest.java`
- deployment documentation for DeepSeek/Qwen environment variables.

### Task 1: Build the OpenAI-Compatible Client Contract

- [ ] **Step 1: Write failing client tests**

Using a local mock HTTP server, verify:

- POST to configured `/chat/completions`;
- Bearer API key;
- configured model;
- JSON response extraction;
- 429/5xx/network/timeout mapped to typed failure;
- no API key in logs or exceptions.

- [ ] **Step 2: Run and verify failure**

```bash
cd backend
mvn -Dtest=OpenAiCompatibleLlmClientTest test
```

Expected: FAIL because client classes do not exist.

- [ ] **Step 3: Extend LLM configuration**

Add enabled, baseUrl, apiKey, model, intentTimeoutMs, responseTimeoutMs, confidenceThreshold, maxContextBytes, and maxToolContextBytes.

- [ ] **Step 4: Implement LlmClient**

Interface:

```java
public interface LlmClient {
    LlmCallResult complete(LlmStage stage, List<ChatMessage> messages);
}
```

The implementation uses a stage-specific RestClient request timeout and no automatic retries.

- [ ] **Step 5: Run client tests**

```bash
cd backend
mvn -Dtest=OpenAiCompatibleLlmClientTest test
```

Expected: PASS for both DeepSeek-like and Qwen-like fixture responses.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/kylinops/llm backend/src/main/java/com/kylinops/config/KylinOpsConfig.java backend/src/main/resources/application*.yml backend/src/test/java/com/kylinops/llm
git commit -m "feat(llm): 添加兼容模型客户端"
```

### Task 2: Add Validated Hybrid Intent Resolution

- [ ] **Step 1: Write failing hybrid intent tests**

Cover:

- clear rule result does not call LLM;
- UNKNOWN calls LLM;
- valid JSON above threshold returns approved intent/params;
- invalid enum, low confidence, unknown parameter, malformed JSON, and timeout fall back;
- COMMAND_EXECUTION and prompt-injection results cannot be overridden.

- [ ] **Step 2: Run and verify failure**

```bash
cd backend
mvn -Dtest=HybridIntentServiceTest test
```

Expected: FAIL.

- [ ] **Step 3: Implement IntentResolution**

```java
public record IntentResolution(
        IntentType intent,
        Map<String, Object> parameters,
        ResolutionSource source,
        double confidence,
        String fallbackReason) {}
```

- [ ] **Step 4: Implement strict parser**

Use Jackson tree parsing. Allow only existing `IntentType` values and per-intent parameter keys:

- service: `serviceName`, `operation`;
- process: `pid`;
- network: `port`;
- logs: `serviceName`, `lines`;
- disk: no model-selected paths in phase 1.

- [ ] **Step 5: Integrate after PromptInjectionDetector**

Replace direct `intentClassifier.classify()` consumption in AgentOrchestrator with `HybridIntentService.resolve()`. Pass only validated params to existing `ToolPlanningService`.

- [ ] **Step 6: Run intent and security tests**

```bash
cd backend
mvn -Dtest=HybridIntentServiceTest,AgentOrchestratorSecurityTest,ToolPlanningServiceTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/kylinops/agent backend/src/test/java/com/kylinops/agent
git commit -m "feat(agent): 增加受限意图增强"
```

### Task 3: Build Complete Tool Context Policies

- [ ] **Step 1: Write registry-enumeration test**

Load production ToolRegistry and assert every enabled non-mock tool name has exactly one `LlmToolContextPolicy`.

- [ ] **Step 2: Run and verify failure**

```bash
cd backend
mvn -Dtest=LlmToolContextPolicyRegistryTest test
```

Expected: FAIL because policies are missing.

- [ ] **Step 3: Implement policies**

Create policies for all ten production tools. Each policy:

- copies only explicit fields;
- removes command lines, environment values, secrets, and unbounded raw text;
- truncates to 4KB;
- marks all strings as untrusted data.

- [ ] **Step 4: Implement overall sanitizer**

Limit total serialized context to 32KB and fail closed to template response if any tool lacks policy.

- [ ] **Step 5: Run policy tests**

```bash
cd backend
mvn -Dtest=LlmToolContextPolicyRegistryTest test
```

Expected: PASS with exactly ten production policies.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/kylinops/agent/intelligence backend/src/test/java/com/kylinops/agent/intelligence
git commit -m "feat(llm): 限制工具结果上下文"
```

### Task 4: Add Grounded Response Generation and Indirect-Injection Defense

- [ ] **Step 1: Write failing response tests**

Cover:

- BLOCK, CONFIRM, GENERAL_CHAT, UNKNOWN, and empty ToolResult never call LLM;
- current-request ToolResults can be summarized;
- malicious text inside journal/process output is treated as data;
- model-introduced unsupported numbers/statuses trigger template fallback;
- client failure triggers template fallback.

- [ ] **Step 2: Run and verify failure**

```bash
cd backend
mvn -Dtest=HybridResponseServiceTest,IndirectPromptInjectionTest test
```

Expected: FAIL.

- [ ] **Step 3: Implement HybridResponseService**

Check deterministic-only cases first, sanitize context, call LLM, validate response, and return `AgentResponseBuilder` output on any failure.

- [ ] **Step 4: Implement pragmatic fact validator**

Reject:

- percentages/numbers absent from sanitized context;
- service state words contradicting context;
- claims that an action executed for CONFIRM/BLOCK;
- command recommendations not present in safe suggestions.

Keep validator narrow; do not build a general NLP verifier.

- [ ] **Step 5: Integrate AgentOrchestrator**

Replace final direct responseBuilder call with hybrid response service after risk/tool execution. Preserve structured response fields unchanged.

- [ ] **Step 6: Run response and orchestration tests**

```bash
cd backend
mvn -Dtest=HybridResponseServiceTest,IndirectPromptInjectionTest,AgentOrchestratorIntegrationTest,AgentOrchestratorSecurityTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/kylinops/agent backend/src/test/java/com/kylinops/agent
git commit -m "feat(agent): 基于真实工具结果生成回复"
```

### Task 5: Persist LLM Call Summaries Without Sensitive Content

- [ ] **Step 1: Write failing audit tests**

Assert persisted record contains auditId, stage, model, duration, success/degraded, and reason; it must not contain API key, prompts, hidden reasoning, or raw tool output.

- [ ] **Step 2: Run and verify failure**

```bash
cd backend
mvn -Dtest=LlmCallAuditTest test
```

Expected: FAIL.

- [ ] **Step 3: Add V3 migration and entity**

Create `kylin_llm_call_record` with bounded summary fields and indexes on auditId/createdAt.

- [ ] **Step 4: Record calls in one focused service**

Do not let the HTTP client write audit records directly. Decorate `LlmClient` with an auditing component receiving the current auditId.

- [ ] **Step 5: Run audit tests**

```bash
cd backend
mvn -Dtest=LlmCallAuditTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/kylinops/audit backend/src/main/resources/db/migration/V3__llm_call_audit.sql backend/src/test
git commit -m "feat(audit): 记录模型调用摘要"
```

### Task 6: Verify Both Providers and Fallback

- [ ] **Step 1: Add provider contract profiles**

Create test fixtures for DeepSeek and Qwen OpenAI-compatible response shapes without real API keys.

- [ ] **Step 2: Run contract and full backend tests**

```bash
cd backend
mvn -B clean test
```

Expected: PASS.

- [ ] **Step 3: Run real-provider smoke manually in protected environment**

DeepSeek:

```bash
LLM_ENABLED=true LLM_BASE_URL=<deepseek-url> LLM_API_KEY=<secret> LLM_MODEL=<model> \
java -jar target/kylin-ops-guard.jar --spring.profiles.active=dev
```

Repeat with Qwen configuration. Never save secrets or raw responses.

- [ ] **Step 4: Verify fallback**

Start with invalid key/blocked network and send all four core requests. Expected: rule/template behavior remains functional and risk decisions match baseline.

- [ ] **Step 5: Update deployment docs**

Document provider configuration matrix and no-auto-retry behavior.

- [ ] **Step 6: Commit**

```bash
git add backend/src/test docs/deploy
git commit -m "test(llm): 验证双模型兼容与降级"
```
