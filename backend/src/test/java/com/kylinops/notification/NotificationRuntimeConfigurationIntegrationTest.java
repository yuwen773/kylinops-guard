package com.kylinops.notification;

import com.kylinops.notification.config.NotificationChannelCommand;
import com.kylinops.notification.config.NotificationChannelRepository;
import com.kylinops.notification.config.NotificationConfigurationService;
import com.kylinops.notification.config.NotificationSecretCipher;
import com.kylinops.notification.config.NotificationSettingsEntity;
import com.kylinops.notification.config.NotificationSettingsRepository;
import com.sun.net.httpserver.HttpServer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-01 Plan 01 — Task 4 (integration test).
 *
 * <p>端到端验证:<b>无需重启</b>的情况下,新写入的通道在 {@code emit} 时被读取并发送,
 * 关闭设置后下一次 emit 不再调用 dispatcher。</p>
 *
 * <p>零外网依赖:使用 JDK 内置 {@code HttpServer} 占位,收到请求即通过
 * {@link LinkedBlockingQueue} 暴露,断言侧只需看 "收到 / 没收到"。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("P1-01 T4 — Notification 运行时配置端到端")
class NotificationRuntimeConfigurationIntegrationTest {

    @Autowired
    private NotificationConfigurationService configurationService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationSettingsRepository settingsRepository;

    @Autowired
    private NotificationChannelRepository channelRepository;

    @Autowired
    private NotificationRecordRepository notificationRecordRepository;

    @Autowired
    private NotificationSecretCipher cipher;

    @PersistenceContext
    private EntityManager em;

    private HttpServer mockServer;
    private LinkedBlockingQueue<String> receivedRequests;
    private int mockPort;

    @BeforeEach
    @Transactional
    void setUp() throws IOException {
        // 1. 清理两张管理表 + 通知记录表(保证每个 test 独立;
        //    H2 DB_CLOSE_DELAY=-1 跨上下文共享,其他 test class 可能留记录)
        channelRepository.deleteAllInBatch();
        settingsRepository.deleteAllInBatch();
        notificationRecordRepository.deleteAllInBatch();

        // 2. 初始化 singleton settings(initialize 通常在启动期执行,这里手动复现)
        NotificationSettingsEntity settings = new NotificationSettingsEntity();
        settings.setEnabled(false);
        settings.setDryRun(false);
        settingsRepository.saveAndFlush(settings);

        // 3. 校验 cipher 已配置(test profile 由 application-test.yml 注入 master-key)
        assertThat(cipher.isConfigured())
                .as("测试 master key 应已配置,否则无法创建加密 secret")
                .isTrue();

        // 4. 启动本地 mock HTTP server(零外网)
        receivedRequests = new LinkedBlockingQueue<>();
        mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockServer.createContext("/hook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            receivedRequests.offer(new String(body));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        mockServer.start();
        mockPort = mockServer.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    @Test
    @DisplayName("enabled=false 时 emit 不调用 dispatcher(无 HTTP 请求)")
    void disabledSetting_skipsDispatcher() throws InterruptedException {
        // settings.enabled = false(默认)
        notificationService.emit(testBusinessEvent());

        // 等待 1 秒,确保即便异步也走完了 — 应没有 HTTP 请求
        assertThat(receivedRequests.poll(1, TimeUnit.SECONDS))
                .as("enabled=false 时应无 HTTP 请求")
                .isNull();
    }

    @Test
    @DisplayName("运行时新增通道 + 启用 settings → 下一次 emit 命中该通道")
    void savedChannelIsUsedByNextNotificationWithoutRestart() throws InterruptedException {
        // 1. 创建 WEBHOOK 通道(指向 mock server)
        NotificationChannelCommand webhookCommand = NotificationChannelCommand.builder()
                .id("webhook-runtime")
                .type(ChannelType.WEBHOOK)
                .enabled(true)
                .url("http://127.0.0.1:" + mockPort + "/hook")
                .timeoutMs(5000)
                .version(0L)
                .build();
        configurationService.createChannel(webhookCommand);

        // 2. 启用通知设置(全局 enabled=true)
        configurationService.updateSettings(
                new com.kylinops.notification.config.NotificationSettingsCommand(
                        true, false, 0L));

        // 3. 触发一条业务事件
        notificationService.emit(testBusinessEvent());

        // 4. 验证 mock server 收到了请求 — 无需重启,运行时的 snapshot 已生效
        String received = receivedRequests.poll(5, TimeUnit.SECONDS);
        assertThat(received)
                .as("运行时新增的通道应在 emit 时被命中,无需重启")
                .isNotNull();
        assertThat(received).contains("\"eventType\":\"SERVICE_ABNORMAL\"");
    }

    @Test
    @DisplayName("auditId 为 null 时记录也保持 null(不转换为空字符串)")
    void nullAuditIdIsPreservedNotConvertedToEmptyString() {
        // 创建通道 + 启用 settings
        configurationService.createChannel(NotificationChannelCommand.builder()
                .id("webhook-audit-null")
                .type(ChannelType.WEBHOOK)
                .enabled(true)
                .url("http://127.0.0.1:" + mockPort + "/hook")
                .timeoutMs(5000)
                .version(0L)
                .build());
        configurationService.updateSettings(
                new com.kylinops.notification.config.NotificationSettingsCommand(
                        true, false, 0L));

        // 发送 auditId = null 的事件(TEST 类型常见)
        String presetEventId = UUID.randomUUID().toString();
        NotificationEvent event = NotificationEvent.builder()
                .eventId(presetEventId)
                .eventType(NotificationEventType.SERVICE_ABNORMAL)
                .severity(NotificationSeverity.INFO)
                .title("t").summary("s").detail("d")
                .sessionId(UUID.randomUUID().toString())
                .auditId(null)
                .build();
        notificationService.emit(event);

        // 等异步调度完成
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        // 验证:记录的 auditId 保持 null,而不是 "" — 用预设 eventId 精确查询
        @SuppressWarnings("unchecked")
        List<NotificationRecord> records = em.createQuery(
                        "select r from NotificationRecord r where r.eventId = :eventId")
                .setParameter("eventId", presetEventId)
                .getResultList();
        assertThat(records)
                .as("emit 后必须能查到本次事件的记录")
                .hasSize(1);
        NotificationRecord rec = records.get(0);
        assertThat(rec.getAuditId())
                .as("auditId=null 必须原样保留,不允许转换为空字符串")
                .isNull();
        assertThat(rec.getEventType())
                .as("记录 eventType 必须从事件继承")
                .isEqualTo(NotificationEventType.SERVICE_ABNORMAL);
    }

    private NotificationEvent testBusinessEvent() {
        return NotificationEvent.builder()
                .eventType(NotificationEventType.SERVICE_ABNORMAL)
                .severity(NotificationSeverity.INFO)
                .title("测试事件")
                .summary("运行时配置集成测试")
                .detail("端到端验证通道运行时生效")
                .sessionId(UUID.randomUUID().toString())
                .auditId(UUID.randomUUID().toString())
                .serviceName("nginx")
                .rcaConfidence(0.85)
                .build();
    }
}