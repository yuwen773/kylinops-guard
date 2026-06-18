package com.kylinops.notification.config;

import com.kylinops.notification.ChannelType;
import com.kylinops.notification.NotificationConfig;
import com.kylinops.notification.NotificationConfig.ChannelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通知中心运行时配置服务 — 唯一权威的"配置写者 + 快照发布者"。
 *
 * <h3>关键不变量 (P1-01 Plan 01 — Task 3)</h3>
 * <ul>
 *   <li>YAML 只在两张管理表都为空时一次性导入,之后数据库为唯一权威</li>
 *   <li>{@link #snapshot()} 仅在事务 after-commit 后发布新值,杜绝回滚前状态泄漏</li>
 *   <li>任何 secret 在写入前必须经 {@link NotificationSecretCipher#encrypt(String)} 加密,
 *       读取 snapshot 时解密 — 持久化层永远只见密文</li>
 *   <li>soft-delete 后 channel_id 不可重用;UUID 类冲突由
 *       {@link NotificationConfigurationConflictException} 抛出</li>
 *   <li>乐观锁冲突由 JPA {@code @Version} + 手动 version 比对共同保护</li>
 * </ul>
 */
@Service
public class NotificationConfigurationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationConfigurationService.class);
    private static final short SINGLETON_ID = 1;
    private static final int MIN_TIMEOUT_MS = 500;
    private static final int MAX_TIMEOUT_MS = 30_000;
    private static final int DEFAULT_TIMEOUT_MS = 3000;
    /** channel_id 长度上限,与 SQL {@code VARCHAR(100)} 对齐。 */
    private static final int MAX_CHANNEL_ID_LENGTH = 100;

    private final NotificationSettingsRepository settingsRepository;
    private final NotificationChannelRepository channelRepository;
    private final NotificationSecretCipher cipher;

    /**
     * 运行时快照。{@link AtomicReference} 保证可见性;新值仅在事务 after-commit
     * 时由 {@link #publishAfterCommit(RuntimeNotificationConfig)} 写入。
     */
    private final AtomicReference<RuntimeNotificationConfig> snapshot =
            new AtomicReference<>(RuntimeNotificationConfig.empty());

    @org.springframework.beans.factory.annotation.Autowired
    public NotificationConfigurationService(NotificationSettingsRepository settingsRepository,
                                             NotificationChannelRepository channelRepository,
                                             NotificationSecretCipher cipher) {
        this(settingsRepository, channelRepository, cipher, null);
    }

    /**
     * 显式构造器(测试用):允许注入 {@code null} 的 transactionTemplate —
     * 但调用方仍必须保证事务上下文(由 {@code @Transactional} 提供)。
     */
    public NotificationConfigurationService(NotificationSettingsRepository settingsRepository,
                                             NotificationChannelRepository channelRepository,
                                             NotificationSecretCipher cipher,
                                             org.springframework.transaction.support.TransactionTemplate ignored) {
        this.settingsRepository = settingsRepository;
        this.channelRepository = channelRepository;
        this.cipher = cipher;
    }

    // ============================================================
    // 初始化 — 启动时调用一次
    // ============================================================

    /**
     * 应用启动时调用一次。
     *
     * <p>规则:</p>
     * <ol>
     *   <li>若两张管理表都为空 → 从 YAML 导入并立即发布快照</li>
     *   <li>否则仅校验现有行(解密所有 secret),成功则发布快照</li>
     *   <li>任何解密失败 → 抛 {@link IllegalStateException},阻止启动</li>
     * </ol>
     *
     * @param yaml 启动期 YAML 配置(发送平面);只在两张表都为空时被引用
     */
    @Transactional
    public void initialize(NotificationConfig yaml) {
        boolean settingsEmpty = settingsRepository.findById(SINGLETON_ID).isEmpty();
        boolean channelsEmpty = channelRepository.findAllByDeletedAtIsNullOrderByChannelIdAsc().isEmpty();

        if (settingsEmpty && channelsEmpty) {
            log.info("通知中心管理表为空,从 YAML 一次性导入");
            importFromYaml(yaml);
        } else {
            log.info("通知中心管理表已存在数据,数据库为唯一权威(忽略 YAML 变更)");
        }

        // 校验 + 发布 — 即使刚导入也要走完整路径以确保 secret 可解密
        RuntimeNotificationConfig fresh = buildSnapshotFromDatabase();
        snapshot.set(fresh);
        log.info("通知中心运行时快照已发布: enabled={}, dryRun={}, channels={}",
                fresh.enabled(), fresh.dryRun(), fresh.channels().size());
    }

    private void importFromYaml(NotificationConfig yaml) {
        NotificationSettingsEntity settings = new NotificationSettingsEntity();
        settings.setId(SINGLETON_ID);
        settings.setEnabled(yaml != null && yaml.isEnabled());
        settings.setDryRun(yaml != null && yaml.isDryRun());
        settingsRepository.saveAndFlush(settings);

        if (yaml == null || yaml.getChannels() == null) {
            return;
        }
        for (ChannelConfig src : yaml.getChannels()) {
            NotificationChannelEntity entity = new NotificationChannelEntity();
            entity.setChannelId(src.getId());
            entity.setChannelType(src.getType());
            entity.setEnabled(src.isEnabled());
            entity.setUrl(src.getUrl());
            Integer timeout = src.getTimeoutMs();
            entity.setTimeoutMs((timeout == null || timeout <= 0) ? DEFAULT_TIMEOUT_MS : timeout);
            entity.setEncryptedSecret(encryptForStorage(src.getSecret()));
            channelRepository.saveAndFlush(entity);
        }
    }

    private RuntimeNotificationConfig buildSnapshotFromDatabase() {
        if (!cipher.isConfigured()) {
            // cipher 未配置但表中可能有密文 — 必须在启动期立即失败
            boolean anySecret = channelRepository.findAllByDeletedAtIsNullOrderByChannelIdAsc()
                    .stream().anyMatch(e -> e.getEncryptedSecret() != null);
            if (anySecret) {
                throw new IllegalStateException(
                        "通知密文已存在但 cipher 未配置:请配置 KYLINOPS_NOTIFICATION_MASTER_KEY");
            }
        }

        NotificationSettingsEntity settings = settingsRepository.findById(SINGLETON_ID).orElseThrow(
                () -> new IllegalStateException("通知设置行不存在 — initialize 流程异常"));
        List<NotificationChannelEntity> entities =
                channelRepository.findAllByDeletedAtIsNullOrderByChannelIdAsc();

        List<ChannelConfig> channels = new ArrayList<>(entities.size());
        for (NotificationChannelEntity e : entities) {
            channels.add(toChannelConfig(e));
        }
        return new RuntimeNotificationConfig(settings.isEnabled(), settings.isDryRun(), channels);
    }

    private ChannelConfig toChannelConfig(NotificationChannelEntity e) {
        String secret = decryptIfPresent(e.getEncryptedSecret());
        return ChannelConfig.builder()
                .id(e.getChannelId())
                .type(e.getChannelType())
                .enabled(e.isEnabled())
                .url(e.getUrl())
                .secret(secret)
                .timeoutMs(e.getTimeoutMs() != null ? e.getTimeoutMs() : DEFAULT_TIMEOUT_MS)
                .build();
    }

    private String encryptForStorage(String plain) {
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        ensureCipherConfigured();
        return cipher.encrypt(plain);
    }

    private String decryptIfPresent(String envelope) {
        if (envelope == null) {
            return null;
        }
        ensureCipherConfigured();
        return cipher.decrypt(envelope);
    }

    private void ensureCipherConfigured() {
        if (!cipher.isConfigured()) {
            throw new IllegalStateException("通知密文已存在但 cipher 未配置");
        }
    }

    // ============================================================
    // 公共 API — 读快照 / 改设置 / CRUD 通道
    // ============================================================

    /** 当前已发布的运行时快照。空集合表示尚未初始化。 */
    public RuntimeNotificationConfig snapshot() {
        return snapshot.get();
    }

    @Transactional(readOnly = true)
    public NotificationSettingsModel getSettings() {
        NotificationSettingsEntity e = settingsRepository.findById(SINGLETON_ID).orElseThrow(
                () -> new IllegalStateException("通知设置行不存在"));
        return NotificationSettingsModel.builder()
                .enabled(e.isEnabled())
                .dryRun(e.isDryRun())
                .version(e.getVersion())
                .build();
    }

    /**
     * 查询全部未删除通道的模型列表（管理 API 使用）。
     */
    @Transactional(readOnly = true)
    public List<NotificationChannelModel> listChannels() {
        return channelRepository.findAllByDeletedAtIsNullOrderByChannelIdAsc()
                .stream()
                .map(this::toModel)
                .toList();
    }

    /**
     * 更新全局设置。{@code command.version()} 必须与当前实体一致,否则抛冲突异常。
     */
    @Transactional
    public NotificationSettingsModel updateSettings(NotificationSettingsCommand command) {
        NotificationSettingsEntity e = settingsRepository.findById(SINGLETON_ID).orElseThrow(
                () -> new IllegalStateException("通知设置行不存在"));
        if (e.getVersion() != command.version()) {
            throw new NotificationConfigurationConflictException(
                    "settings version mismatch: expected=" + command.version()
                            + " actual=" + e.getVersion());
        }
        e.setEnabled(command.enabled());
        e.setDryRun(command.dryRun());
        try {
            settingsRepository.saveAndFlush(e);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new NotificationConfigurationConflictException(
                    "settings version conflict (concurrent update)");
        }

        publishAfterCommit(buildSnapshotFromDatabase());
        return NotificationSettingsModel.builder()
                .enabled(e.isEnabled())
                .dryRun(e.isDryRun())
                .version(e.getVersion())
                .build();
    }

    /**
     * 创建新通道。ID 已存在(含软删除)即抛冲突。
     */
    @Transactional
    public NotificationChannelModel createChannel(NotificationChannelCommand command) {
        validateChannelId(command.id(), true);
        validateType(command.type());
        validateUrl(command.url());
        validateTimeout(command.timeoutMs());
        validateSecretRequired(command);

        if (channelRepository.existsByChannelId(command.id())) {
            throw new NotificationConfigurationConflictException(
                    "channel id already exists: " + command.id());
        }

        NotificationChannelEntity entity = new NotificationChannelEntity();
        entity.setChannelId(command.id());
        entity.setChannelType(command.type());
        entity.setEnabled(command.enabled());
        entity.setUrl(command.url());
        entity.setTimeoutMs(command.timeoutMs());
        entity.setEncryptedSecret(encryptForStorage(command.secret()));
        channelRepository.saveAndFlush(entity);

        publishAfterCommit(buildSnapshotFromDatabase());
        return toModel(entity);
    }

    /**
     * 更新已存在通道。
     *
     * <p>secret 处理:</p>
     * <ul>
     *   <li>{@code command.clearSecret() == true} → 强制 null(FEISHU 拒绝)</li>
     *   <li>{@code command.secret()} 非空 → 替换</li>
     *   <li>{@code command.secret()} null/空 → 保留原值</li>
     * </ul>
     */
    @Transactional
    public NotificationChannelModel updateChannel(String id, NotificationChannelCommand command) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("channel id is required");
        }
        validateType(command.type());
        validateUrl(command.url());
        validateTimeout(command.timeoutMs());

        NotificationChannelEntity e = channelRepository.findByChannelId(id).orElseThrow(
                () -> new NotificationConfigurationConflictException(
                        "channel not found: " + id));
        if (e.getDeletedAt() != null) {
            throw new NotificationConfigurationConflictException(
                    "channel not found: " + id);
        }
        if (e.getVersion() != command.version()) {
            throw new NotificationConfigurationConflictException(
                    "channel version mismatch: expected=" + command.version()
                            + " actual=" + e.getVersion());
        }

        // secret 三态处理 + 校验在解析出有效 secret 之后进行
        String effectivePlainSecret;
        if (command.clearSecret()) {
            if (command.type() == ChannelType.FEISHU) {
                throw new IllegalArgumentException(
                        "FEISHU channel must not clear secret");
            }
            effectivePlainSecret = null;
        } else if (command.secret() != null && !command.secret().isEmpty()) {
            effectivePlainSecret = command.secret();
        } else {
            // 保留原值 — 解密现有密文以校验 FEISHU 必填
            effectivePlainSecret = decryptIfPresent(e.getEncryptedSecret());
        }
        validateSecretState(command.type(), effectivePlainSecret);

        e.setEnabled(command.enabled());
        e.setUrl(command.url());
        e.setTimeoutMs(command.timeoutMs());

        if (command.clearSecret()) {
            e.setEncryptedSecret(null);
        } else if (command.secret() != null && !command.secret().isEmpty()) {
            e.setEncryptedSecret(encryptForStorage(command.secret()));
        }
        // else: 保持原 encryptedSecret 不动

        try {
            channelRepository.saveAndFlush(e);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new NotificationConfigurationConflictException(
                    "channel version conflict (concurrent update)");
        }

        publishAfterCommit(buildSnapshotFromDatabase());
        return toModel(e);
    }

    /**
     * 软删除通道 — 设置 {@code deleted_at = now()},快照中将不再出现。
     */
    @Transactional
    public void deleteChannel(String id, long version) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("channel id is required");
        }
        NotificationChannelEntity e = channelRepository.findByChannelId(id).orElseThrow(
                () -> new NotificationConfigurationConflictException(
                        "channel not found: " + id));
        if (e.getDeletedAt() != null) {
            throw new NotificationConfigurationConflictException(
                    "channel not found: " + id);
        }
        if (e.getVersion() != version) {
            throw new NotificationConfigurationConflictException(
                    "channel version mismatch: expected=" + version
                            + " actual=" + e.getVersion());
        }
        e.setDeletedAt(LocalDateTime.now());
        try {
            channelRepository.saveAndFlush(e);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new NotificationConfigurationConflictException(
                    "channel version conflict (concurrent delete)");
        }
        publishAfterCommit(buildSnapshotFromDatabase());
    }

    /**
     * 仅为测试发送解析一个 {@link ChannelConfig}。规则:
     * <ul>
     *   <li>若 ID 已存在(快照或数据库),使用已存储的解密 secret(忽略 command.secret())</li>
     *   <li>若 ID 不存在,使用 command 中的 secret(测试创建场景)</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public NotificationConfig.ChannelConfig resolveForTest(NotificationChannelCommand command) {
        if (command == null || command.id() == null) {
            throw new IllegalArgumentException("channel id is required");
        }
        return channelRepository.findByChannelId(command.id())
                .filter(e -> e.getDeletedAt() == null)
                .map(this::toChannelConfig)
                .orElseGet(() -> ChannelConfig.builder()
                        .id(command.id())
                        .type(command.type())
                        .enabled(command.enabled())
                        .url(command.url())
                        .secret(command.secret())
                        .timeoutMs(command.timeoutMs())
                        .build());
    }

    // ============================================================
    // 校验工具
    // ============================================================

    private void validateChannelId(String id, boolean required) {
        if (id == null || id.isBlank()) {
            if (required) {
                throw new IllegalArgumentException("channel id is required");
            }
            return;
        }
        if (id.length() > MAX_CHANNEL_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "channel id length must be <= " + MAX_CHANNEL_ID_LENGTH);
        }
        // 限 ASCII 字母数字 + 连字符/下划线/点,避免日志/序列化歧义
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.';
            if (!ok) {
                throw new IllegalArgumentException(
                        "channel id may only contain ASCII letters, digits, '-', '_', '.'");
            }
        }
    }

    private void validateType(ChannelType type) {
        if (type == null) {
            throw new IllegalArgumentException("channel type is required");
        }
    }

    private void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("url is not a valid URI");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("url must use http or https scheme");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("url must contain a host");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("url must not contain userinfo");
        }
    }

    private void validateTimeout(int timeoutMs) {
        if (timeoutMs < MIN_TIMEOUT_MS || timeoutMs > MAX_TIMEOUT_MS) {
            throw new IllegalArgumentException(
                    "timeout must be in [" + MIN_TIMEOUT_MS + ", " + MAX_TIMEOUT_MS + "] ms");
        }
    }

    private void validateSecretRequired(NotificationChannelCommand command) {
        if (command.type() == ChannelType.FEISHU) {
            boolean hasSecret = command.clearSecret()
                    ? false
                    : (command.secret() != null && !command.secret().isEmpty());
            if (!hasSecret) {
                throw new IllegalArgumentException(
                        "FEISHU channel requires a non-empty secret");
            }
        }
    }

    /**
     * 已解析有效 secret(可空)后的状态校验 — 用于 update 路径中
     * "原值保留" 的场景。
     */
    private void validateSecretState(ChannelType type, String effectivePlainSecret) {
        if (type == ChannelType.FEISHU) {
            if (effectivePlainSecret == null || effectivePlainSecret.isEmpty()) {
                throw new IllegalArgumentException(
                        "FEISHU channel requires a non-empty secret");
            }
        }
    }

    private NotificationChannelModel toModel(NotificationChannelEntity e) {
        return NotificationChannelModel.builder()
                .id(e.getChannelId())
                .type(e.getChannelType())
                .enabled(e.isEnabled())
                .url(e.getUrl())
                .hasSecret(e.getEncryptedSecret() != null)
                .timeoutMs(e.getTimeoutMs())
                .version(e.getVersion())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    // ============================================================
    // 事务 after-commit 发布 — 杜绝回滚前快照泄漏
    // ============================================================

    private void publishAfterCommit(RuntimeNotificationConfig candidate) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    snapshot.set(candidate);
                }
            });
        } else {
            // 非事务上下文(单元测试 / 调试入口) — 直接发布,避免无限等待
            snapshot.set(candidate);
        }
    }
}