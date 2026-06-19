package com.kylinops.notification.config;

import com.kylinops.notification.ChannelType;
import com.kylinops.notification.NotificationConfig;
import com.kylinops.notification.NotificationConfig.ChannelConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-01 Plan 01 — Task 3 (Service-level tests).
 *
 * <p>Covers the persistence-vs-YAML "database wins after first boot" contract,
 * snapshot publication rules, optimistic locking, soft-delete ID re-use
 * prohibition, and per-type secret validation.</p>
 */
@DataJpaTest
@Import({NotificationManagementBeansConfig.class, NotificationConfigurationService.class})
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("P1-01 T3 — NotificationConfigurationService")
class NotificationConfigurationServiceTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private NotificationSettingsRepository settingsRepository;

    @Autowired
    private NotificationChannelRepository channelRepository;

    @Autowired
    private NotificationSecretCipher cipher;

    @Autowired
    private NotificationConfigurationService service;

    /**
     * H2 内存数据库在 {@code DB_CLOSE_DELAY=-1} 下跨 Spring 上下文缓存复用,
     * 必须在每个测试前清空两张管理表,避免测试间数据污染(尤其是
     * {@code snapshotPublicationIsTransactionAfterCommit} 会真实提交
     * {@code will-commit} 通道)。
     *
     * <p>{@code @DirtiesContext(AFTER_EACH_TEST_METHOD)} 已重建 service 上下文,
     * 此处只需清 DB。</p>
     */
    @org.junit.jupiter.api.BeforeEach
    void cleanManagementTables() {
        // Spring Data 仓储自带事务,即便 @BeforeEach 在无事务上下文亦可清理
        channelRepository.deleteAllInBatch();
        settingsRepository.deleteAllInBatch();
    }

    @Autowired
    private TransactionTemplate transactionTemplate;

    private NotificationConfig.ChannelConfig feishu(String id, String secret) {
        return ChannelConfig.builder()
                .id(id)
                .type(ChannelType.FEISHU)
                .enabled(true)
                .url("https://open.feishu.cn/hook/" + id)
                .secret(secret)
                .timeoutMs(5000)
                .build();
    }

    private NotificationConfig.ChannelConfig webhook(String id) {
        return ChannelConfig.builder()
                .id(id)
                .type(ChannelType.WEBHOOK)
                .enabled(true)
                .url("https://hooks.example.com/" + id)
                .secret(null)
                .timeoutMs(3000)
                .build();
    }

    private NotificationConfig yamlConfig(boolean enabled, boolean dryRun, ChannelConfig... channels) {
        NotificationConfig cfg = new NotificationConfig();
        cfg.setEnabled(enabled);
        cfg.setDryRun(dryRun);
        cfg.setChannels(List.of(channels));
        return cfg;
    }

    @Test
    @DisplayName("YAML imports once; subsequent restart ignores changed YAML")
    void importsYamlOnceThenDatabaseBecomesAuthoritative() {
        NotificationConfig yaml = yamlConfig(true, false, feishu("feishu-demo", "secret-a"));
        service.initialize(yaml);

        // Restart with different YAML — DB should remain authoritative.
        NotificationConfig yamlChanged = yamlConfig(false, true, webhook("changed-yaml"));
        service.initialize(yamlChanged);

        RuntimeNotificationConfig snapshot = service.snapshot();
        assertThat(snapshot.enabled()).isTrue();
        assertThat(snapshot.dryRun()).isFalse();
        assertThat(snapshot.channels()).extracting(ChannelConfig::getId)
                .containsExactly("feishu-demo");
        // Confirm decryption succeeded.
        assertThat(snapshot.channels().get(0).getSecret()).isEqualTo("secret-a");
    }

    @Test
    @DisplayName("Snapshot contains only active (non-deleted) channels")
    void snapshotExcludesSoftDeletedChannels() {
        NotificationConfig yaml = yamlConfig(true, false,
                feishu("keep", "s1"), feishu("drop", "s2"));
        service.initialize(yaml);

        NotificationChannelEntity dropEntity = channelRepository.findByChannelId("drop")
                .orElseThrow();
        service.deleteChannel("drop", dropEntity.getVersion());

        assertThat(service.snapshot().channels())
                .extracting(ChannelConfig::getId)
                .containsExactly("keep");
    }

    @Test
    @DisplayName("createChannel immediately changes the snapshot")
    void updateImmediatelyChangesTheSnapshot() {
        service.initialize(yamlConfig(true, false));

        service.createChannel(NotificationChannelCommand.builder()
                .id("created-via-api")
                .type(ChannelType.WEBHOOK)
                .enabled(true)
                .url("https://hooks.example.com/api")
                .timeoutMs(2000)
                .build());

        assertThat(service.snapshot().channels())
                .extracting(ChannelConfig::getId)
                .containsExactly("created-via-api");
    }

    @Test
    @DisplayName("Soft-deleted channel ID cannot be reused")
    void deletedIdsCannotBeReused() {
        service.initialize(yamlConfig(true, false, feishu("recycled", "s")));
        NotificationChannelEntity recycled = channelRepository.findByChannelId("recycled")
                .orElseThrow();
        service.deleteChannel("recycled", recycled.getVersion());

        assertThatThrownBy(() -> service.createChannel(NotificationChannelCommand.builder()
                .id("recycled")
                .type(ChannelType.WEBHOOK)
                .enabled(true)
                .url("https://hooks.example.com/again")
                .timeoutMs(2000)
                .build()))
                .isInstanceOf(NotificationConfigurationConflictException.class)
                .hasMessageContaining("recycled");
    }

    @Test
    @DisplayName("Stale version on settings update fails with conflict exception")
    void staleSettingsVersionFails() {
        service.initialize(yamlConfig(true, false));
        NotificationSettingsModel settings = service.getSettings();
        long staleVersion = settings.version();

        // First update succeeds.
        service.updateSettings(NotificationSettingsCommand.builder()
                .enabled(false)
                .dryRun(false)
                .version(staleVersion)
                .build());

        // Second update with the now-stale version must fail.
        assertThatThrownBy(() -> service.updateSettings(NotificationSettingsCommand.builder()
                .enabled(true)
                .dryRun(false)
                .version(staleVersion)
                .build()))
                .isInstanceOf(NotificationConfigurationConflictException.class);
    }

    @Test
    @DisplayName("Stale version on channel update fails with conflict exception")
    void staleChannelVersionFails() {
        service.initialize(yamlConfig(true, false, feishu("a", "s")));
        NotificationChannelEntity before = channelRepository.findByChannelId("a").orElseThrow();

        service.updateChannel("a", NotificationChannelCommand.builder()
                .type(ChannelType.FEISHU)
                .enabled(false)
                .url("https://open.feishu.cn/hook/a")
                .secret("s")
                .timeoutMs(3000)
                .version(before.getVersion())
                .build());

        NotificationChannelEntity after = channelRepository.findByChannelId("a").orElseThrow();
        assertThat(after.isEnabled()).isFalse();
        long stale = before.getVersion();

        assertThatThrownBy(() -> service.updateChannel("a", NotificationChannelCommand.builder()
                .type(ChannelType.FEISHU)
                .enabled(true)
                .url("https://open.feishu.cn/hook/a")
                .secret("s")
                .timeoutMs(3000)
                .version(stale)
                .build()))
                .isInstanceOf(NotificationConfigurationConflictException.class);
    }

    @Test
    @DisplayName("FEISHU without secret is rejected")
    void feishuRequiresSecret() {
        service.initialize(yamlConfig(true, false));

        assertThatThrownBy(() -> service.createChannel(NotificationChannelCommand.builder()
                .id("needs-secret")
                .type(ChannelType.FEISHU)
                .enabled(true)
                .url("https://open.feishu.cn/hook/x")
                .timeoutMs(2000)
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret");
    }

    @Test
    @DisplayName("WEBHOOK without secret is accepted")
    void webhookAllowsNoSecret() {
        service.initialize(yamlConfig(true, false));

        NotificationChannelModel model = service.createChannel(NotificationChannelCommand.builder()
                .id("webhook-no-secret")
                .type(ChannelType.WEBHOOK)
                .enabled(true)
                .url("https://hooks.example.com/anon")
                .timeoutMs(2000)
                .build());

        assertThat(model.type()).isEqualTo(ChannelType.WEBHOOK);
        assertThat(service.snapshot().channels())
                .extracting(ChannelConfig::getId)
                .containsExactly("webhook-no-secret");
    }

    @Test
    @DisplayName("URL must be http or https and not contain userinfo")
    void urlValidation() {
        service.initialize(yamlConfig(true, false));

        // Wrong scheme.
        assertThatThrownBy(() -> service.createChannel(NotificationChannelCommand.builder()
                .id("ftp-bad")
                .type(ChannelType.WEBHOOK)
                .enabled(true)
                .url("ftp://hooks.example.com/x")
                .timeoutMs(2000)
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http");

        // Userinfo rejected.
        assertThatThrownBy(() -> service.createChannel(NotificationChannelCommand.builder()
                .id("userinfo-bad")
                .type(ChannelType.WEBHOOK)
                .enabled(true)
                .url("https://user:pass@hooks.example.com/x")
                .timeoutMs(2000)
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userinfo");
    }

    @Test
    @DisplayName("Timeout must be between 500 and 30000 ms")
    void timeoutValidation() {
        service.initialize(yamlConfig(true, false));

        assertThatThrownBy(() -> service.createChannel(NotificationChannelCommand.builder()
                .id("slow")
                .type(ChannelType.WEBHOOK)
                .enabled(true)
                .url("https://hooks.example.com/slow")
                .timeoutMs(499)
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");

        assertThatThrownBy(() -> service.createChannel(NotificationChannelCommand.builder()
                .id("huge")
                .type(ChannelType.WEBHOOK)
                .enabled(true)
                .url("https://hooks.example.com/huge")
                .timeoutMs(30001)
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    @DisplayName("Encrypted rows require a valid master key at startup — fails when cipher missing")
    void encryptedRowsRequireValidMasterKey() {
        // Seed a row with a fake envelope that the real cipher can decrypt.
        NotificationConfig yaml = yamlConfig(true, false, feishu("with-secret", "plain"));
        service.initialize(yaml);
        // Re-initialize with a cipher built from a different master key.
        NotificationSecretCipher wrongCipher = new NotificationSecretCipher(
                "QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI"); // identical bytes (test passes)
        NotificationConfigurationService sameCipherService = new NotificationConfigurationService(
                settingsRepository, channelRepository, wrongCipher,
                null, transactionTemplate);

        // Now build a cipher with a *different* master key and try to start fresh.
        NotificationSecretCipher wrongKey = new NotificationSecretCipher(
                java.util.Base64.getEncoder().encodeToString(new byte[32])); // all zeros
        NotificationConfigurationService brokenService = new NotificationConfigurationService(
                settingsRepository, channelRepository, wrongKey,
                null, transactionTemplate);

        assertThatThrownBy(() -> brokenService.initialize(yamlConfig(true, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("plain");

        // Sanity: same cipher still works.
        sameCipherService.initialize(yamlConfig(true, false));
        assertThat(sameCipherService.snapshot().channels())
                .extracting(ChannelConfig::getId)
                .containsExactly("with-secret");
    }

    @Test
    @DisplayName("resolveForTest assembles a ChannelConfig from command; new channel uses command secret")
    void resolveForTestNewChannel() {
        service.initialize(yamlConfig(true, false));
        NotificationChannelCommand cmd = NotificationChannelCommand.builder()
                .id("test-cmd")
                .type(ChannelType.WEBHOOK)
                .enabled(true)
                .url("https://hooks.example.com/test")
                .secret("inline-secret")
                .timeoutMs(2500)
                .build();

        ChannelConfig resolved = service.resolveForTest(cmd);
        assertThat(resolved.getId()).isEqualTo("test-cmd");
        assertThat(resolved.getSecret()).isEqualTo("inline-secret");
        assertThat(resolved.getType()).isEqualTo(ChannelType.WEBHOOK);
    }

    @Test
    @DisplayName("resolveForTest for saved channel with empty command secret uses stored decrypted secret")
    void resolveForTestSavedChannelUsesStoredSecret() {
        service.initialize(yamlConfig(true, false, feishu("persisted", "stored-secret")));
        // Saved channel, command provides no secret.
        NotificationChannelCommand cmd = NotificationChannelCommand.builder()
                .id("persisted")
                .type(ChannelType.FEISHU)
                .enabled(true)
                .url("https://open.feishu.cn/hook/persisted")
                .secret(null)
                .timeoutMs(5000)
                .build();

        ChannelConfig resolved = service.resolveForTest(cmd);
        assertThat(resolved.getSecret()).isEqualTo("stored-secret");
    }

    @Test
    @DisplayName("Snapshot unchanged before commit, changes after commit, unchanged after rollback")
    void snapshotPublicationIsTransactionAfterCommit() {
        service.initialize(yamlConfig(true, false));

        // Capture pre-state.
        List<String> pre = service.snapshot().channels().stream()
                .map(ChannelConfig::getId).toList();

        // Inside a rolled-back transaction, the snapshot must not change.
        transactionTemplate.executeWithoutResult(status -> {
            try {
                service.createChannel(NotificationChannelCommand.builder()
                        .id("will-rollback")
                        .type(ChannelType.WEBHOOK)
                        .enabled(true)
                        .url("https://hooks.example.com/rb")
                        .timeoutMs(2000)
                        .build());
            } catch (RuntimeException ex) {
                status.setRollbackOnly();
                throw ex;
            }
            status.setRollbackOnly();
        });

        assertThat(service.snapshot().channels().stream().map(ChannelConfig::getId).toList())
                .isEqualTo(pre);

        // Now create in a committed transaction.
        service.createChannel(NotificationChannelCommand.builder()
                .id("will-commit")
                .type(ChannelType.WEBHOOK)
                .enabled(true)
                .url("https://hooks.example.com/commit")
                .timeoutMs(2000)
                .build());

        assertThat(service.snapshot().channels().stream().map(ChannelConfig::getId).toList())
                .containsExactly("will-commit");
    }

    @Test
    @DisplayName("Stored channel secret is ciphertext, never plaintext")
    void storedSecretIsCiphertext() {
        service.initialize(yamlConfig(true, false, feishu("enc-only", "plain-text-secret")));

        NotificationChannelEntity entity = channelRepository.findByChannelId("enc-only")
                .orElseThrow();
        assertThat(entity.getEncryptedSecret()).isNotNull();
        assertThat(entity.getEncryptedSecret()).doesNotContain("plain-text-secret");
        assertThat(entity.getEncryptedSecret()).startsWith("v1:");
    }

    @Test
    @DisplayName("Existing channel kept secret when update supplies empty secret")
    void updateWithoutSecretPreservesStoredSecret() {
        service.initialize(yamlConfig(true, false, feishu("preserve", "original-secret")));

        NotificationChannelEntity before = channelRepository.findByChannelId("preserve").orElseThrow();
        service.updateChannel("preserve", NotificationChannelCommand.builder()
                .type(ChannelType.FEISHU)
                .enabled(false)
                .url("https://open.feishu.cn/hook/preserve")
                .secret(null)
                .timeoutMs(7000)
                .version(before.getVersion())
                .build());

        assertThat(service.snapshot().channels().get(0).getSecret())
                .isEqualTo("original-secret");
    }

    @Test
    @DisplayName("FEISHU cannot clear secret via update")
    void feishuCannotClearSecret() {
        service.initialize(yamlConfig(true, false, feishu("no-clear", "have-secret")));
        NotificationChannelEntity before = channelRepository.findByChannelId("no-clear").orElseThrow();

        assertThatThrownBy(() -> service.updateChannel("no-clear", NotificationChannelCommand.builder()
                .type(ChannelType.FEISHU)
                .enabled(true)
                .url("https://open.feishu.cn/hook/no-clear")
                .secret("")
                .clearSecret(true)
                .timeoutMs(3000)
                .version(before.getVersion())
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret");
    }

    @Test
    @DisplayName("Settings returned by service expose version for optimistic concurrency")
    void getSettingsExposesVersion() {
        service.initialize(yamlConfig(true, false));
        NotificationSettingsModel settings = service.getSettings();
        assertThat(settings.enabled()).isTrue();
        assertThat(settings.dryRun()).isFalse();
        assertThat(settings.version()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("Initial snapshot has empty channels and disabled when YAML is empty")
    void initialSnapshotEmptyWhenYamlEmpty() {
        service.initialize(yamlConfig(false, false));
        RuntimeNotificationConfig snapshot = service.snapshot();
        assertThat(snapshot.enabled()).isFalse();
        assertThat(snapshot.channels()).isEmpty();
    }
}