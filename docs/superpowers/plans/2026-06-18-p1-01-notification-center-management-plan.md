# P1-01 Notification Center Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add database-backed notification configuration, channel CRUD and connection testing, frontend management and audit displays, and polished Feishu cards without changing notification trigger semantics.

**Architecture:** Persist global settings and channels in normalized JPA entities, encrypt channel secrets with an environment master key, and expose an immutable runtime snapshot through `NotificationConfigurationService`. Existing notification dispatch and channel implementations consume that snapshot; a protected management API drives a Vue page, while test sends reuse channel implementations but write `TEST` notification records.

**Tech Stack:** Java 17, Spring Boot 3.3, Spring Data JPA, Flyway, AES-GCM, OkHttp/MockWebServer, Vue 3, TypeScript, Element Plus, Vitest, Playwright.

---

## Preconditions and execution rules

- Execute this plan in an isolated worktree using `superpowers:using-git-worktrees`; the source workspace currently contains unrelated user changes.
- Use `superpowers:test-driven-development` for every production change.
- Use `superpowers:verification-before-completion` before claiming completion.
- Do not modify notification trigger conditions in `AgentOrchestrator`.
- Do not add DingTalk, WeCom, email, rule DSL, retries, aggregation, or a general notification-history page.
- Never log or return plaintext/ciphertext secrets.
- Run backend commands from `backend/` and frontend commands from `frontend/`.

## File map

### Backend persistence and crypto

- Create `backend/src/main/resources/db/migration/V6__notification_management.sql` — settings/channel tables, record discriminator, nullable audit ID, indexes.
- Create `backend/src/main/java/com/kylinops/notification/config/NotificationSettingsEntity.java` — singleton global settings row.
- Create `backend/src/main/java/com/kylinops/notification/config/NotificationChannelEntity.java` — versioned channel configuration and soft delete.
- Create `backend/src/main/java/com/kylinops/notification/config/NotificationSettingsRepository.java`.
- Create `backend/src/main/java/com/kylinops/notification/config/NotificationChannelRepository.java`.
- Create `backend/src/main/java/com/kylinops/notification/config/NotificationSecretCipher.java` — AES-256-GCM envelope.
- Create `backend/src/main/java/com/kylinops/notification/config/NotificationManagementProperties.java` — master-key/public-URL binding.
- Create `backend/src/main/java/com/kylinops/notification/config/NotificationManagementBeansConfig.java` — properties and cipher beans.
- Create `backend/src/main/java/com/kylinops/notification/config/NotificationConfigurationService.java` — import, CRUD, validation, snapshot.
- Create `backend/src/main/java/com/kylinops/notification/config/RuntimeNotificationConfig.java` — immutable runtime settings/channels.
- Create `backend/src/main/java/com/kylinops/notification/config/NotificationSettingsCommand.java`.
- Create `backend/src/main/java/com/kylinops/notification/config/NotificationChannelCommand.java`.
- Create `backend/src/main/java/com/kylinops/notification/config/NotificationSettingsModel.java`.
- Create `backend/src/main/java/com/kylinops/notification/config/NotificationChannelModel.java`.

### Backend API and test sending

- Create `backend/src/main/java/com/kylinops/notification/api/NotificationManagementController.java`.
- Create `backend/src/main/java/com/kylinops/notification/api/NotificationSettingsView.java`.
- Create `backend/src/main/java/com/kylinops/notification/api/NotificationChannelView.java`.
- Create `backend/src/main/java/com/kylinops/notification/api/NotificationSettingsUpdateRequest.java`.
- Create `backend/src/main/java/com/kylinops/notification/api/NotificationChannelUpsertRequest.java`.
- Create `backend/src/main/java/com/kylinops/notification/api/NotificationChannelTestRequest.java`.
- Create `backend/src/main/java/com/kylinops/notification/NotificationTestService.java`.
- Modify `backend/src/main/java/com/kylinops/common/BusinessException.java` — add 409 factory.
- Modify `backend/src/main/java/com/kylinops/notification/NotificationRecord.java`.
- Modify `backend/src/main/java/com/kylinops/notification/NotificationRecordRepository.java`.
- Modify `backend/src/main/java/com/kylinops/notification/NotificationRecordSummary.java`.
- Modify `backend/src/main/java/com/kylinops/notification/NotificationEventType.java`.

### Runtime integration and cards

- Modify `backend/src/main/java/com/kylinops/notification/NotificationService.java`.
- Modify `backend/src/main/java/com/kylinops/notification/NotificationDispatcher.java`.
- Modify `backend/src/main/java/com/kylinops/notification/channel/FeishuChannel.java`.
- Create `backend/src/main/java/com/kylinops/notification/NotificationPublicLinkProperties.java`.
- Modify `backend/src/main/resources/application.yml`.
- Modify `backend/src/main/resources/application-test.yml`.

### Frontend

- Create `frontend/src/types/notification.ts`.
- Create `frontend/src/api/notifications.ts`.
- Create `frontend/src/pages/NotificationCenter/index.vue`.
- Create `frontend/src/pages/NotificationCenter/index.spec.ts`.
- Modify `frontend/src/router/index.ts`.
- Modify `frontend/src/layouts/AppLayout.vue`.
- Modify `frontend/src/layouts/AppLayout.spec.ts`.
- Modify `frontend/src/types/audit.ts`.
- Modify `frontend/src/pages/AuditLog/index.vue`.
- Modify `frontend/src/pages/AuditLog/index.spec.ts`.
- Create `frontend/tests/e2e/notification-center.spec.ts`.
- Create `frontend/playwright.live.config.ts`.
- Create `backend/src/main/resources/application-e2e.yml`.
- Modify `frontend/vite.config.ts` — configurable dev proxy target for live E2E.
- Modify `frontend/tests/e2e/fixtures.ts` only if the shared API fixture must expose the new response shape.

## Task 1: Migrate the schema and record discriminator

**Files:**

- Create: `backend/src/main/resources/db/migration/V6__notification_management.sql`
- Modify: `backend/src/main/java/com/kylinops/notification/NotificationRecord.java`
- Modify: `backend/src/main/java/com/kylinops/notification/NotificationRecordRepository.java`
- Modify: `backend/src/main/java/com/kylinops/notification/NotificationRecordSummary.java`
- Modify: `backend/src/main/java/com/kylinops/notification/NotificationEventType.java`
- Test: `backend/src/test/java/com/kylinops/notification/NotificationRecordRepositoryTest.java`
- Test: `backend/src/test/java/com/kylinops/database/FlywayPostgresMigrationTest.java`

- [ ] **Step 1: Write failing repository tests**

Add tests proving that a TEST record can have `auditId=null`, `eventType=TEST`, and can be queried independently:

```java
@Test
void testRecordAllowsNullAuditIdAndQueriesNewestFirst() {
    NotificationRecord older = testRecord("test-a", "feishu-prod",
            NotificationEventType.TEST, LocalDateTime.of(2026, 6, 18, 10, 0));
    NotificationRecord newer = testRecord("test-b", "feishu-prod",
            NotificationEventType.TEST, LocalDateTime.of(2026, 6, 18, 11, 0));
    repository.saveAllAndFlush(List.of(older, newer));

    List<NotificationRecord> records =
            repository.findTop20ByEventTypeOrderByCreatedAtDesc(NotificationEventType.TEST);

    assertThat(records).extracting(NotificationRecord::getEventId)
            .containsExactly("test-b", "test-a");
    assertThat(records).allMatch(record -> record.getAuditId() == null);
}
```

Add a migration assertion that `notification_records.audit_id` is nullable and the three new objects exist:

```java
assertColumnExists(dataSource, "notification_records", "event_type");
assertColumnNullable(dataSource, "notification_records", "audit_id");
assertTableExists(dataSource, "notification_settings");
assertTableExists(dataSource, "notification_channels");
```

- [ ] **Step 2: Run the focused tests and verify failure**

Run:

```powershell
mvn -q -Dtest=NotificationRecordRepositoryTest,FlywayPostgresMigrationTest test
```

Expected: FAIL because `eventType`, query methods, tables, and V6 migration do not exist.

- [ ] **Step 3: Add the minimal migration**

Create `V6__notification_management.sql` with:

```sql
ALTER TABLE notification_records ADD COLUMN event_type VARCHAR(40);
ALTER TABLE notification_records ALTER COLUMN audit_id DROP NOT NULL;
CREATE INDEX idx_notification_event_created
    ON notification_records (event_type, created_at DESC);

CREATE TABLE notification_settings (
    id SMALLINT PRIMARY KEY,
    enabled BOOLEAN NOT NULL,
    dry_run BOOLEAN NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_notification_settings_singleton CHECK (id = 1)
);

CREATE TABLE notification_channels (
    channel_id VARCHAR(100) PRIMARY KEY,
    channel_type VARCHAR(20) NOT NULL,
    enabled BOOLEAN NOT NULL,
    url VARCHAR(2048) NOT NULL,
    encrypted_secret TEXT,
    timeout_ms INTEGER NOT NULL,
    deleted_at TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_notification_channels_active
    ON notification_channels (deleted_at, channel_id);
```

Use SQL syntax already supported by the project’s H2/PostgreSQL migration tests. If `ALTER COLUMN ... DROP NOT NULL` is not portable in the existing harness, use the smallest project-consistent variant and assert both databases.

- [ ] **Step 4: Map the new record field and queries**

Add to `NotificationEventType`:

```java
TEST
```

Add to `NotificationRecord`:

```java
@Enumerated(EnumType.STRING)
@Column(length = 40)
private NotificationEventType eventType;
```

Change `auditId` to nullable and add:

```java
List<NotificationRecord> findTop20ByEventTypeOrderByCreatedAtDesc(
        NotificationEventType eventType);

Optional<NotificationRecord> findFirstByChannelIdAndEventTypeOrderByCreatedAtDesc(
        String channelId, NotificationEventType eventType);
```

Expose `eventType` through `NotificationRecordSummary`, still excluding request/response bodies.

- [ ] **Step 5: Run focused tests**

Run:

```powershell
mvn -q -Dtest=NotificationRecordRepositoryTest,FlywayPostgresMigrationTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/resources/db/migration/V6__notification_management.sql `
  backend/src/main/java/com/kylinops/notification/NotificationRecord.java `
  backend/src/main/java/com/kylinops/notification/NotificationRecordRepository.java `
  backend/src/main/java/com/kylinops/notification/NotificationRecordSummary.java `
  backend/src/main/java/com/kylinops/notification/NotificationEventType.java `
  backend/src/test/java/com/kylinops/notification/NotificationRecordRepositoryTest.java `
  backend/src/test/java/com/kylinops/database/FlywayPostgresMigrationTest.java
git commit -m "feat(notification): 添加通知配置数据模型"
```

## Task 2: Implement authenticated secret encryption

**Files:**

- Create: `backend/src/main/java/com/kylinops/notification/config/NotificationSecretCipher.java`
- Create: `backend/src/main/java/com/kylinops/notification/config/NotificationManagementProperties.java`
- Create: `backend/src/main/java/com/kylinops/notification/config/NotificationManagementBeansConfig.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/resources/application-test.yml`
- Test: `backend/src/test/java/com/kylinops/notification/config/NotificationSecretCipherTest.java`
- Test: `backend/src/test/java/com/kylinops/notification/config/NotificationManagementBeansConfigTest.java`

- [ ] **Step 1: Write failing cipher tests**

Cover round-trip, randomized ciphertext, wrong key, tampering, absent key, and invalid key length:

```java
@Test
void encryptUsesRandomNonceAndDecrypts() {
    NotificationSecretCipher cipher = cipherWithKey(KEY_A);
    String first = cipher.encrypt("secret-value");
    String second = cipher.encrypt("secret-value");

    assertThat(first).startsWith("v1:");
    assertThat(second).isNotEqualTo(first);
    assertThat(cipher.decrypt(first)).isEqualTo("secret-value");
    assertThat(cipher.decrypt(second)).isEqualTo("secret-value");
}

@Test
void wrongKeyFailsClosedWithoutLeakingSecret() {
    String encrypted = cipherWithKey(KEY_A).encrypt("do-not-leak");
    assertThatThrownBy(() -> cipherWithKey(KEY_B).decrypt(encrypted))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining("do-not-leak")
            .hasMessageNotContaining(encrypted);
}
```

- [ ] **Step 2: Run and verify failure**

Run:

```powershell
mvn -q -Dtest=NotificationSecretCipherTest test
```

Expected: FAIL because the cipher class does not exist.

- [ ] **Step 3: Implement the minimal AES-GCM envelope**

Use a versioned string:

```text
v1:<base64-nonce>:<base64-ciphertext-and-tag>
```

Core contract:

```java
public final class NotificationSecretCipher {
    public NotificationSecretCipher(String base64MasterKey) { ... }
    public boolean isConfigured() { ... }
    public String encrypt(String plaintext) { ... }
    public String decrypt(String envelope) { ... }
}
```

Implementation requirements:

- Decode exactly 32 bytes from `KYLINOPS_NOTIFICATION_MASTER_KEY`.
- Use `AES/GCM/NoPadding`, 12-byte `SecureRandom` nonce, 128-bit tag.
- Permit an unconfigured instance only while no secret operation is requested.
- Wrap cryptographic failures in a stable non-sensitive `IllegalStateException`.

- [ ] **Step 4: Run cipher tests**

Run:

```powershell
mvn -q -Dtest=NotificationSecretCipherTest test
```

Expected: PASS.

- [ ] **Step 5: Bind and construct the cipher through Spring**

Create:

```java
@ConfigurationProperties(prefix = "kylinops.notification.management")
public record NotificationManagementProperties(
        String masterKey,
        String publicBaseUrl) {
}
```

Register it without adding Secret handling to the existing YAML import DTO:

```java
@Configuration
@EnableConfigurationProperties(NotificationManagementProperties.class)
public class NotificationManagementBeansConfig {
    @Bean
    NotificationSecretCipher notificationSecretCipher(
            NotificationManagementProperties properties) {
        return new NotificationSecretCipher(properties.masterKey());
    }
}
```

Bind the environment:

```yaml
kylinops:
  notification:
    management:
      master-key: ${KYLINOPS_NOTIFICATION_MASTER_KEY:}
      public-base-url: ${KYLINOPS_PUBLIC_BASE_URL:}
```

Set a fake valid Base64-encoded 32-byte key in `application-test.yml`. Add a Spring context test proving binding succeeds and invalid Base64/length fails without including the configured value in the exception.

- [ ] **Step 6: Run cipher and Spring binding tests**

Run:

```powershell
mvn -q -Dtest=NotificationSecretCipherTest,NotificationManagementBeansConfigTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/com/kylinops/notification/config/NotificationSecretCipher.java `
  backend/src/main/java/com/kylinops/notification/config/NotificationManagementProperties.java `
  backend/src/main/java/com/kylinops/notification/config/NotificationManagementBeansConfig.java `
  backend/src/main/resources/application.yml backend/src/main/resources/application-test.yml `
  backend/src/test/java/com/kylinops/notification/config/NotificationSecretCipherTest.java `
  backend/src/test/java/com/kylinops/notification/config/NotificationManagementBeansConfigTest.java
git commit -m "feat(notification): 加密存储通知密钥"
```

## Task 3: Build persistent configuration and runtime snapshots

**Files:**

- Create: `backend/src/main/java/com/kylinops/notification/config/NotificationSettingsEntity.java`
- Create: `backend/src/main/java/com/kylinops/notification/config/NotificationChannelEntity.java`
- Create: `backend/src/main/java/com/kylinops/notification/config/NotificationSettingsRepository.java`
- Create: `backend/src/main/java/com/kylinops/notification/config/NotificationChannelRepository.java`
- Create: `backend/src/main/java/com/kylinops/notification/config/RuntimeNotificationConfig.java`
- Create: `backend/src/main/java/com/kylinops/notification/config/NotificationSettingsCommand.java`
- Create: `backend/src/main/java/com/kylinops/notification/config/NotificationChannelCommand.java`
- Create: `backend/src/main/java/com/kylinops/notification/config/NotificationSettingsModel.java`
- Create: `backend/src/main/java/com/kylinops/notification/config/NotificationChannelModel.java`
- Create: `backend/src/main/java/com/kylinops/notification/config/NotificationConfigurationService.java`
- Test: `backend/src/test/java/com/kylinops/notification/config/NotificationConfigurationServiceTest.java`
- Test: `backend/src/test/java/com/kylinops/notification/config/NotificationConfigurationRepositoryTest.java`

- [ ] **Step 1: Write failing repository and service tests**

Cover:

- empty database imports YAML exactly once;
- subsequent startup ignores changed YAML;
- snapshot contains decrypted active channels only;
- update immediately changes the returned snapshot;
- soft deletion removes a channel from the snapshot;
- deleted IDs cannot be reused;
- stale versions fail with a dedicated conflict exception;
- FEISHU requires Secret; WEBHOOK permits no Secret;
- URL/timeout/channel-ID validation;
- encrypted rows require a valid master key at startup.

Representative test:

```java
@Test
void importsYamlOnceThenDatabaseBecomesAuthoritative() {
    NotificationConfig yaml = yamlConfig(true, false, feishu("feishu-demo", "secret-a"));
    NotificationConfigurationService first = service(yaml);
    first.initialize();

    yaml.setChannels(List.of(webhook("changed-yaml")));
    NotificationConfigurationService restarted = service(yaml);
    restarted.initialize();

    assertThat(restarted.snapshot().channels())
            .extracting(NotificationConfig.ChannelConfig::getId)
            .containsExactly("feishu-demo");
}
```

- [ ] **Step 2: Run and verify failure**

Run:

```powershell
mvn -q -Dtest=NotificationConfigurationServiceTest,NotificationConfigurationRepositoryTest test
```

Expected: FAIL because entities and service do not exist.

- [ ] **Step 3: Implement focused entities and repositories**

Use:

```java
@Version
private long version;
```

Repository contracts:

```java
Optional<NotificationSettingsEntity> findById(short id);
List<NotificationChannelEntity> findAllByDeletedAtIsNullOrderByChannelIdAsc();
boolean existsByChannelId(String channelId);
```

Do not add entity relationships to notification records.

- [ ] **Step 4: Implement immutable snapshot and initialization**

Use a record:

```java
public record RuntimeNotificationConfig(
        boolean enabled,
        boolean dryRun,
        List<NotificationConfig.ChannelConfig> channels) {

    public RuntimeNotificationConfig {
        channels = List.copyOf(channels);
    }
}
```

`NotificationConfigurationService` owns:

```java
private final AtomicReference<RuntimeNotificationConfig> snapshot =
        new AtomicReference<>(new RuntimeNotificationConfig(false, false, List.of()));

public RuntimeNotificationConfig snapshot() {
    return snapshot.get();
}
```

Initialization rules:

- Import only when both management tables are empty.
- Encrypt YAML Secret before persistence.
- Validate/decrypt all active rows before publishing the snapshot.
- Fail startup if any stored Secret cannot be decrypted.

- [ ] **Step 5: Implement CRUD service methods**

Keep the persistence service independent of API DTOs so this task compiles before Task 5. Expose service-layer commands and models:

```java
NotificationSettingsModel getSettings();
NotificationSettingsModel updateSettings(NotificationSettingsCommand command);
NotificationChannelModel createChannel(NotificationChannelCommand command);
NotificationChannelModel updateChannel(String id, NotificationChannelCommand command);
void deleteChannel(String id, long version);
NotificationConfig.ChannelConfig resolveForTest(NotificationChannelCommand command);
```

Task 5 maps API request/view classes explicitly to these internal contracts.

Construct and validate the candidate snapshot before saving. Publish it strictly after transaction commit:

```java
private void publishAfterCommit(RuntimeNotificationConfig candidate) {
    TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    snapshot.set(candidate);
                }
            });
}
```

All mutating public methods are `@Transactional`, flush repositories before registration, and call `publishAfterCommit(candidate)`. Tests must prove the snapshot remains unchanged before commit, changes after commit, and remains unchanged after rollback.

- [ ] **Step 6: Run focused tests**

Run:

```powershell
mvn -q -Dtest=NotificationConfigurationServiceTest,NotificationConfigurationRepositoryTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/com/kylinops/notification/config `
  backend/src/test/java/com/kylinops/notification/config
git commit -m "feat(notification): 实现数据库运行时配置"
```

## Task 4: Switch the sending path to runtime configuration

**Files:**

- Modify: `backend/src/main/java/com/kylinops/notification/NotificationService.java`
- Modify: `backend/src/main/java/com/kylinops/notification/NotificationDispatcher.java`
- Modify: `backend/src/main/resources/application-test.yml`
- Test: `backend/src/test/java/com/kylinops/notification/NotificationServiceTest.java`
- Create: `backend/src/test/java/com/kylinops/notification/NotificationRuntimeConfigurationIntegrationTest.java`

- [ ] **Step 1: Rewrite focused tests against the snapshot provider**

Replace direct `NotificationConfig` setup in `NotificationServiceTest` with mocked `NotificationConfigurationService`:

```java
when(configurationService.snapshot()).thenReturn(
        new RuntimeNotificationConfig(false, false, List.of()));
service.emit(event);
verify(dispatcher, never()).dispatchAsync(any());
```

Add an integration test:

```java
@Test
void savedChannelIsUsedByNextNotificationWithoutRestart() {
    configurationService.createChannel(webhookCommand(mockServer.url("/hook")));
    configurationService.updateSettings(
            new NotificationSettingsCommand(true, false, 0));

    notificationService.emit(testBusinessEvent());

    assertThat(mockServer.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
}
```

- [ ] **Step 2: Run and verify failure**

Run:

```powershell
mvn -q -Dtest=NotificationServiceTest,NotificationRuntimeConfigurationIntegrationTest test
```

Expected: FAIL because service/dispatcher still read startup-bound `NotificationConfig`.

- [ ] **Step 3: Make the minimal runtime integration**

`NotificationService.emit()` reads `configurationService.snapshot().enabled()`.

`NotificationDispatcher.doDispatch()` captures one snapshot at the start:

```java
RuntimeNotificationConfig runtime = configurationService.snapshot();
List<NotificationConfig.ChannelConfig> configs = runtime.channels().stream()
        .filter(NotificationConfig.ChannelConfig::isEnabled)
        .filter(channel -> channel.getUrl() != null && !channel.getUrl().isBlank())
        .toList();
```

Use `runtime.dryRun()` for the whole dispatch so one event never observes mixed versions.

When records are created, set:

```java
.eventType(event.getEventType())
.auditId(event.getAuditId())
```

Do not convert absent audit IDs to empty strings.

- [ ] **Step 4: Keep tests isolated**

In `application-test.yml`, keep notification disabled and tables empty by default. Tests that exercise configuration must provide a valid test master key through test properties and mock all outbound HTTP.

- [ ] **Step 5: Run notification regression tests**

Run:

```powershell
mvn -q -Dtest="com.kylinops.notification.*Test,com.kylinops.agent.*NotificationTest,com.kylinops.audit.AuditLogNotificationDetailTest" test
```

Expected: PASS with zero real network calls.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/kylinops/notification/NotificationService.java `
  backend/src/main/java/com/kylinops/notification/NotificationDispatcher.java `
  backend/src/main/resources/application-test.yml `
  backend/src/test/java/com/kylinops/notification/NotificationServiceTest.java `
  backend/src/test/java/com/kylinops/notification/NotificationRuntimeConfigurationIntegrationTest.java
git commit -m "refactor(notification): 切换为数据库运行时配置"
```

## Task 5: Add management API and optimistic conflict handling

**Files:**

- Create: `backend/src/main/java/com/kylinops/notification/api/NotificationManagementController.java`
- Create: `backend/src/main/java/com/kylinops/notification/api/NotificationSettingsView.java`
- Create: `backend/src/main/java/com/kylinops/notification/api/NotificationChannelView.java`
- Create: `backend/src/main/java/com/kylinops/notification/api/NotificationSettingsUpdateRequest.java`
- Create: `backend/src/main/java/com/kylinops/notification/api/NotificationChannelUpsertRequest.java`
- Modify: `backend/src/main/java/com/kylinops/common/BusinessException.java`
- Test: `backend/src/test/java/com/kylinops/notification/api/NotificationManagementControllerTest.java`
- Test: `backend/src/test/java/com/kylinops/common/GlobalExceptionHandlerTest.java`

- [ ] **Step 1: Write failing MockMvc contract tests**

Cover authenticated GET/PUT/POST/DELETE, unauthenticated rejection, validation errors, secret omission, and 409 conflict:

```java
mockMvc.perform(get("/api/notification/settings")
        .with(user("admin")))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data.channels[0].secretConfigured").value(true))
    .andExpect(jsonPath("$.data.channels[0].secret").doesNotExist())
    .andExpect(jsonPath("$.data.channels[0].encryptedSecret").doesNotExist());

mockMvc.perform(put("/api/notification/channels/feishu-prod")
        .with(user("admin")).with(csrf())
        .contentType(APPLICATION_JSON)
        .content(staleVersionJson))
    .andExpect(status().isConflict())
    .andExpect(jsonPath("$.code").value(409));
```

- [ ] **Step 2: Run and verify failure**

Run:

```powershell
mvn -q -Dtest=NotificationManagementControllerTest,GlobalExceptionHandlerTest test
```

Expected: FAIL because API DTOs/controller and 409 factory do not exist.

- [ ] **Step 3: Add validated request/view DTOs**

`NotificationChannelUpsertRequest` fields:

```java
@NotBlank @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}")
String channelId;
@NotNull ChannelType type;
boolean enabled;
@NotBlank @Size(max = 2048) String url;
@Size(max = 4096) String secret;
boolean clearSecret;
@Min(500) @Max(30000) Integer timeoutMs;
@NotNull Long version; // null only on create DTO path
```

Use separate create/update validation logic if one DTO would make `version` or immutable `channelId/type` ambiguous.

- [ ] **Step 4: Implement controller endpoints**

```java
@GetMapping("/settings")
@PutMapping("/settings")
@PostMapping("/channels")
@PutMapping("/channels/{channelId}")
@DeleteMapping("/channels/{channelId}")
```

Map service validation to 400, missing/soft-deleted channel to 404, and version conflict to 409. Add:

```java
public static BusinessException conflict(String message) {
    return new BusinessException(409, message);
}
```

Do not log request DTOs because they may contain a new Secret.

- [ ] **Step 5: Run API tests**

Run:

```powershell
mvn -q -Dtest=NotificationManagementControllerTest,GlobalExceptionHandlerTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/kylinops/notification/api `
  backend/src/main/java/com/kylinops/common/BusinessException.java `
  backend/src/test/java/com/kylinops/notification/api/NotificationManagementControllerTest.java `
  backend/src/test/java/com/kylinops/common/GlobalExceptionHandlerTest.java
git commit -m "feat(notification): 添加通知配置管理接口"
```

## Task 6: Add connection testing and recent-test queries

**Files:**

- Create: `backend/src/main/java/com/kylinops/notification/api/NotificationChannelTestRequest.java`
- Create: `backend/src/main/java/com/kylinops/notification/NotificationTestService.java`
- Modify: `backend/src/main/java/com/kylinops/notification/api/NotificationManagementController.java`
- Modify: `backend/src/main/java/com/kylinops/notification/config/NotificationConfigurationService.java`
- Modify: `backend/src/main/java/com/kylinops/notification/NotificationRecordRepository.java`
- Test: `backend/src/test/java/com/kylinops/notification/NotificationTestServiceTest.java`
- Test: `backend/src/test/java/com/kylinops/notification/api/NotificationManagementControllerTest.java`

- [ ] **Step 1: Write failing test-send tests**

Cover:

- draft channel uses submitted values without persistence;
- saved channel reuses stored Secret when request Secret is empty;
- saved WEBHOOK with `clearSecret=true` tests unsigned without changing the row;
- FEISHU rejects `clearSecret=true`;
- success and failure both persist `eventType=TEST`, `auditId=null`;
- recent endpoint returns only TEST records, newest first, maximum 20;
- each channel view receives its own true latest test result.

Representative service test:

```java
NotificationRecordSummary result = testService.test(
        requestForSavedChannel("webhook-prod").withClearSecret(true));

assertThat(result.getEventType()).isEqualTo(NotificationEventType.TEST);
assertThat(result.getAuditId()).isNull();
assertThat(configurationRepository.findById("webhook-prod").orElseThrow()
        .getEncryptedSecret()).isEqualTo(originalCiphertext);
```

- [ ] **Step 2: Run and verify failure**

Run:

```powershell
mvn -q -Dtest=NotificationTestServiceTest,NotificationManagementControllerTest test
```

Expected: FAIL because test service and endpoints do not exist.

- [ ] **Step 3: Implement synchronous administrative test sending**

`NotificationTestService` should:

1. Resolve a temporary `ChannelConfig` from the request.
2. Build a fixed `NotificationEvent` with `eventType=TEST`, INFO severity, a new UUID, and current time.
3. Resolve the existing `NotificationChannel` from the registry.
4. Send synchronously so the HTTP response can show the result.
5. Persist one final SENT/FAILED record; do not use business `NotificationService.emit()` or global `enabled/dryRun`.

Use a draft ID only when no saved ID exists:

```java
String recordChannelId = request.channelId() != null
        ? request.channelId()
        : "test-draft-" + UUID.randomUUID().toString().substring(0, 8);
```

- [ ] **Step 4: Add endpoints**

```java
@PostMapping("/channels/test")
ApiResponse<NotificationRecordSummary> test(@Valid @RequestBody NotificationChannelTestRequest request)

@GetMapping("/test-records")
ApiResponse<List<NotificationRecordSummary>> recentTests(
        @RequestParam(defaultValue = "20") int limit)
```

Clamp `limit` to `[1, 20]`. External HTTP failure returns HTTP 200 with a `FAILED` summary; invalid configuration returns 400.

- [ ] **Step 5: Run focused tests**

Run:

```powershell
mvn -q -Dtest=NotificationTestServiceTest,NotificationManagementControllerTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/kylinops/notification/NotificationTestService.java `
  backend/src/main/java/com/kylinops/notification/NotificationRecordRepository.java `
  backend/src/main/java/com/kylinops/notification/config/NotificationConfigurationService.java `
  backend/src/main/java/com/kylinops/notification/api `
  backend/src/test/java/com/kylinops/notification/NotificationTestServiceTest.java `
  backend/src/test/java/com/kylinops/notification/api/NotificationManagementControllerTest.java
git commit -m "feat(notification): 添加通道连接测试"
```

## Task 7: Polish Feishu cards and audit links

**Files:**

- Create: `backend/src/main/java/com/kylinops/notification/NotificationPublicLinkProperties.java`
- Modify: `backend/src/main/java/com/kylinops/notification/channel/FeishuChannel.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/resources/application-test.yml`
- Test: `backend/src/test/java/com/kylinops/notification/channel/FeishuChannelTest.java`

- [ ] **Step 1: Write failing wire-format tests**

Use MockWebServer to assert:

- concise title and low-saturation severity template;
- summary, key object, occurrence time, audit ID;
- audit button for valid public base URL;
- no button for missing/invalid base URL;
- TEST title and no audit link;
- no raw detail/command field.

```java
JsonNode card = requestJson.path("card");
assertThat(card.path("header").path("title").path("content").asText())
        .isEqualTo("高风险操作已阻断");
assertThat(card.toString()).contains("查看审计详情");
assertThat(card.toString()).contains("/audit?auditId=audit-123");
assertThat(card.toString()).doesNotContain("rm -rf /");
```

- [ ] **Step 2: Run and verify failure**

Run:

```powershell
mvn -q -Dtest=FeishuChannelTest test
```

Expected: FAIL against the current verbose markdown card.

- [ ] **Step 3: Bind the public URL**

`NotificationPublicLinkProperties` receives `NotificationManagementProperties.publicBaseUrl()` from Task 2, validates the optional absolute HTTP(S) URI, and exposes:

```java
Optional<String> auditUrl(String auditId)
```

Invalid/missing values return empty and log one non-sensitive warning at startup.

- [ ] **Step 4: Implement the concise card**

Keep signing and transport unchanged. Replace only `buildCard` composition:

- map severity to Feishu header template;
- choose a short event-specific title;
- add summary and exactly one key-object line;
- add occurred time and audit ID;
- add an action element only when an audit URL exists;
- build a distinct TEST card with no audit fields.

Keep all text passed through sanitizer-safe/truncation helpers.

- [ ] **Step 5: Run channel tests**

Run:

```powershell
mvn -q -Dtest=FeishuChannelTest,WebhookChannelTest test
```

Expected: PASS; Webhook behavior unchanged.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/kylinops/notification/NotificationPublicLinkProperties.java `
  backend/src/main/java/com/kylinops/notification/channel/FeishuChannel.java `
  backend/src/test/java/com/kylinops/notification/channel/FeishuChannelTest.java
git commit -m "feat(notification): 优化飞书告警卡片"
```

## Task 8: Add frontend API contracts and navigation

**Files:**

- Create: `frontend/src/types/notification.ts`
- Create: `frontend/src/api/notifications.ts`
- Create: `frontend/src/api/notifications.spec.ts`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/layouts/AppLayout.vue`
- Modify: `frontend/src/layouts/AppLayout.spec.ts`

- [ ] **Step 1: Write failing API and navigation tests**

API test contract:

```typescript
await getNotificationSettings();
expect(mockedGet).toHaveBeenCalledWith('/api/notification/settings');

await deleteNotificationChannel('feishu-prod', 3);
expect(mockedDel).toHaveBeenCalledWith(
  '/api/notification/channels/feishu-prod',
  { params: { version: 3 } },
);
```

Layout test:

```typescript
expect(wrapper.text()).toContain('通知中心');
expect(wrapper.find('[data-testid="nav-notifications"]').attributes('href'))
  .toBe('/notifications');
```

- [ ] **Step 2: Run and verify failure**

Run:

```powershell
npm run test:unit -- --run src/api/notifications.spec.ts src/layouts/AppLayout.spec.ts
```

Expected: FAIL because the API module, types, route, and nav item do not exist.

- [ ] **Step 3: Define exact frontend types**

Include:

```typescript
export type NotificationChannelType = 'FEISHU' | 'WEBHOOK';
export type NotificationStatus = 'PENDING' | 'SENT' | 'FAILED' | 'SKIPPED';

export interface NotificationChannelView {
  channelId: string;
  type: NotificationChannelType;
  enabled: boolean;
  url: string;
  timeoutMs: number;
  version: number;
  secretConfigured: boolean;
  lastTestResult?: NotificationRecordSummary;
}
```

Request types must represent `secret?: string` and `clearSecret?: boolean`; never model a server-returned Secret.

- [ ] **Step 4: Implement API wrappers and navigation**

Use existing `get/post/put/del` helpers. Add `/notifications` lazy route and an Element Plus notification icon in `AppLayout`.

- [ ] **Step 5: Run focused tests and typecheck**

Run:

```powershell
npm run test:unit -- --run src/api/notifications.spec.ts src/layouts/AppLayout.spec.ts
npm run build
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add frontend/src/types/notification.ts frontend/src/api/notifications.ts `
  frontend/src/api/notifications.spec.ts frontend/src/router/index.ts `
  frontend/src/layouts/AppLayout.vue frontend/src/layouts/AppLayout.spec.ts
git commit -m "feat(frontend): 添加通知中心路由与接口"
```

## Task 9: Build the Notification Center page

**Files:**

- Create: `frontend/src/pages/NotificationCenter/index.vue`
- Create: `frontend/src/pages/NotificationCenter/index.spec.ts`

- [ ] **Step 1: Write failing page behavior tests**

Mock `@/api/notifications` and cover:

- initial settings and recent tests load;
- explicit global save;
- create/edit drawer;
- saved Secret renders placeholder without populating the input;
- eye toggle reveals only newly entered Secret;
- create/update payload omits placeholder and retains Secret when untouched;
- explicit clear with confirmation;
- independent test loading per channel/draft;
- success/failed result rendering;
- recent tests refresh;
- stale-version 409 message;
- soft-delete confirmation.

Representative assertion:

```typescript
expect(wrapper.get('[data-testid="channel-secret-state"]').text()).toContain('已配置');
expect((wrapper.get('[data-testid="channel-secret-input"]').element as HTMLInputElement).value)
  .toBe('');

await wrapper.get('[data-testid="channel-save"]').trigger('click');
expect(updateNotificationChannel).toHaveBeenCalledWith(
  'feishu-prod',
  expect.not.objectContaining({ secret: '••••••' }),
);
```

- [ ] **Step 2: Run and verify failure**

Run:

```powershell
npm run test:unit -- --run src/pages/NotificationCenter/index.spec.ts
```

Expected: FAIL because the page does not exist.

- [ ] **Step 3: Implement state and actions first**

Keep the page local and explicit; do not introduce a store for this single page.

State groups:

```typescript
const settings = ref<NotificationSettingsView | null>(null);
const recentTests = ref<NotificationRecordSummary[]>([]);
const drawerVisible = ref(false);
const editingChannel = ref<NotificationChannelView | null>(null);
const form = reactive<NotificationChannelForm>(emptyForm());
const testingKeys = ref(new Set<string>());
```

Implement `load`, `saveSettings`, `openCreate`, `openEdit`, `saveChannel`, `testChannel`, `deleteChannel`, and `resetDrawer`.

- [ ] **Step 4: Implement the approved layout**

Render:

1. global settings card;
2. responsive channel-card grid;
3. create/edit drawer;
4. recent 20 test records table/list.

Use existing project CSS variables and Element Plus components. Add stable `data-testid` attributes for all tested actions.

The eye icon toggles only `form.secret`. Never synthesize or reveal the stored Secret.

- [ ] **Step 5: Run page tests and build**

Run:

```powershell
npm run test:unit -- --run src/pages/NotificationCenter/index.spec.ts
npm run build
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add frontend/src/pages/NotificationCenter/index.vue `
  frontend/src/pages/NotificationCenter/index.spec.ts
git commit -m "feat(frontend): 实现通知配置页面"
```

## Task 10: Display notification records in Audit Log

**Files:**

- Modify: `frontend/src/types/audit.ts`
- Modify: `frontend/src/pages/AuditLog/index.vue`
- Modify: `frontend/src/pages/AuditLog/index.spec.ts`
- Modify: `frontend/tests/e2e/fixtures.ts` if required by fixture typing

- [ ] **Step 1: Write failing audit-detail tests**

Add a detail fixture with SENT and FAILED records and assert:

```typescript
expect(wrapper.get('[data-testid="audit-tab-notifications"]').text())
  .toContain('通知记录 2');
await wrapper.get('[data-testid="audit-tab-notifications"]').trigger('click');
expect(wrapper.text()).toContain('feishu-prod');
expect(wrapper.text()).toContain('发送成功');
expect(wrapper.text()).toContain('HTTP 500');
expect(wrapper.text()).not.toContain('requestPayload');
expect(wrapper.text()).not.toContain('responseBody');
```

Add an empty-state test.

- [ ] **Step 2: Run and verify failure**

Run:

```powershell
npm run test:unit -- --run src/pages/AuditLog/index.spec.ts
```

Expected: FAIL because `AuditLogDetail` and the drawer do not consume `notificationRecords`.

- [ ] **Step 3: Extend the frontend DTO**

Reuse `NotificationRecordSummary` from `@/types/notification`:

```typescript
notificationRecords?: NotificationRecordSummary[];
```

- [ ] **Step 4: Add the independent notification tab**

The current detail drawer is a long single column. Introduce an `el-tabs` shell with:

- “审计详情” containing all existing content unchanged;
- “通知记录 N” containing a compact table/card list.

Map states:

```typescript
const NOTIFICATION_STATUS_LABELS = {
  PENDING: '发送中',
  SENT: '发送成功',
  FAILED: '发送失败',
  SKIPPED: '已跳过',
} as const;
```

Show channel, status, response code, sent/created time, retry count, and sanitized error only.

- [ ] **Step 5: Run audit tests and build**

Run:

```powershell
npm run test:unit -- --run src/pages/AuditLog/index.spec.ts
npm run build
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add frontend/src/types/audit.ts frontend/src/pages/AuditLog/index.vue `
  frontend/src/pages/AuditLog/index.spec.ts frontend/tests/e2e/fixtures.ts
git commit -m "feat(frontend): 展示审计通知记录"
```

## Task 11: Add end-to-end coverage and complete verification

**Files:**

- Create: `frontend/tests/e2e/notification-center.spec.ts`
- Create: `frontend/playwright.live.config.ts`
- Create: `backend/src/main/resources/application-e2e.yml`
- Modify: `frontend/vite.config.ts`
- Modify: `docs/product/functional-defect-and-roadmap.md` only if the roadmap has a dedicated P1-01 status section that must be updated; otherwise leave it untouched.

- [ ] **Step 1: Write the failing E2E scenario**

This is an opt-in live E2E, separate from the existing mocked `playwright.config.ts`. It uses a real Spring Boot process and a controlled local mock Feishu server:

```typescript
test('configure, test, trigger L4, and inspect audit notification', async ({ page }) => {
  await loginAsAdmin(page);
  await page.goto('/notifications');
  await createFeishuChannel(page, mockFeishuUrl, testSecret);
  await page.getByTestId('channel-test-feishu-e2e').click();
  await expect(page.getByTestId('test-records')).toContainText('发送成功');

  await triggerL4Block(page, 'rm -rf /');
  await openLatestAuditDetail(page);
  await page.getByTestId('audit-tab-notifications').click();
  await expect(page.getByTestId('audit-notification-records')).toContainText('SENT');
});
```

Start the mock Feishu HTTP server in a worker fixture and always close it during teardown.

- [ ] **Step 2: Run the E2E test and verify failure**

Run:

```powershell
npx playwright test --config playwright.live.config.ts tests/e2e/notification-center.spec.ts
```

Expected: FAIL because the live Playwright config and backend E2E profile do not exist.

- [ ] **Step 3: Add an isolated live-backend E2E profile**

`application-e2e.yml` must:

- use a disposable H2 database;
- run Flyway migrations;
- set a fake valid notification master key;
- configure deterministic administrator credentials used only by E2E;
- listen on port 18080;
- start with notification disabled and no outbound channel.

Create `playwright.live.config.ts` with two servers:

```typescript
webServer: [
  {
    command: 'mvn -q -f ../backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=e2e',
    url: 'http://127.0.0.1:18080/api/health',
    reuseExistingServer: false,
    timeout: 180_000,
  },
  {
    command: 'npm run dev -- --host 127.0.0.1 --port 5174',
    url: 'http://127.0.0.1:5174',
    reuseExistingServer: false,
    timeout: 120_000,
    env: { VITE_PROXY_TARGET: 'http://127.0.0.1:18080' },
  },
]
```

Modify `vite.config.ts` so `/api` remains same-origin in the browser:

```typescript
const proxyTarget = process.env.VITE_PROXY_TARGET ?? 'http://127.0.0.1:8080';
// ...
proxy: {
  '/api': {
    target: proxyTarget,
    changeOrigin: true,
    secure: false,
  },
},
```

Do not set `VITE_API_BASE_URL` for live E2E; the API client must continue using relative `/api` URLs so session cookies and CSRF stay same-origin through Vite. Set Playwright `baseURL` to port 5174, `workers: 1`, and `fullyParallel: false`. Keep the existing default Playwright config unchanged for fast mocked E2E.

- [ ] **Step 4: Add only the required fixtures/selectors**

Do not weaken production validation or bypass authentication. Keep the E2E deterministic:

- fixed local server;
- no external network;
- unique channel ID per test;
- cleanup through soft delete or isolated test database.

- [ ] **Step 5: Run focused full-stack verification**

Run:

```powershell
npx playwright test --config playwright.live.config.ts tests/e2e/notification-center.spec.ts
```

Expected: PASS.

- [ ] **Step 6: Run the backend suite**

Run:

```powershell
mvn -q test
```

Expected: all tests pass; no outbound real network calls.

- [ ] **Step 7: Run frontend unit, build, and mocked E2E suites**

Run:

```powershell
npm run test:unit -- --run
npm run build
npm run test:e2e
```

Expected: all tests pass with only pre-existing documented skips.

- [ ] **Step 8: Inspect security-sensitive diffs**

Run:

```powershell
git diff --check
rg -n "encryptedSecret|KYLINOPS_NOTIFICATION_MASTER_KEY|secret" `
  backend/src/main/java/com/kylinops/notification `
  frontend/src/pages/NotificationCenter `
  frontend/src/types/notification.ts
```

Expected:

- no API view exposes `secret` or `encryptedSecret`;
- no logs print request objects or Secret values;
- frontend never receives stored Secret;
- test fixtures contain only fake values.

- [ ] **Step 9: Commit**

```powershell
git add frontend/tests/e2e/notification-center.spec.ts frontend/playwright.live.config.ts `
  frontend/vite.config.ts backend/src/main/resources/application-e2e.yml
git commit -m "test(notification): 覆盖通知管理完整链路"
```

## Final acceptance checklist

- [ ] `/notifications` supports global settings and FEISHU/WEBHOOK CRUD.
- [ ] Saving configuration affects the next notification without restart.
- [ ] Restart preserves database configuration and does not re-merge YAML.
- [ ] Stored Secrets are AES-GCM ciphertext and are never returned by APIs.
- [ ] Missing/wrong master key fails startup when encrypted data exists.
- [ ] Test connection uses current unsaved form values and records SENT/FAILED TEST rows.
- [ ] Each channel card shows its actual latest test result.
- [ ] Audit detail shows formal notification records in an independent tab.
- [ ] Feishu cards are concise and link to `/audit?auditId=...` when configured.
- [ ] Existing L4, prompt injection, L2 confirmation, service, and disk trigger semantics are unchanged.
- [ ] Backend, frontend unit, frontend build, and E2E suites pass.
