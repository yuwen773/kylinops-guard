package com.kylinops.chat;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 服务就绪状态跟踪
 * <p>
 * 用于 {@code /api/health/ready} 端点，判断服务是否具备处理请求的能力。
 * 检查两个维度：
 * <ul>
 *   <li>数据库就绪 — DB 迁移 + EntityManagerFactory 初始化完成</li>
 *   <li>安全规则就绪 — RiskRuleEngine 加载完成</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class ReadinessService {

    private final AtomicBoolean databaseReady = new AtomicBoolean(false);
    private final AtomicBoolean rulesReady = new AtomicBoolean(false);

    @PostConstruct
    void init() {
        log.info("ReadinessService 初始化");
    }

    /**
     * 标记数据库就绪。
     */
    public void markDatabaseReady() {
        databaseReady.set(true);
        log.info("数据库就绪");
    }

    /**
     * 标记安全规则就绪。
     */
    public void markRulesReady() {
        rulesReady.set(true);
        log.info("安全规则就绪");
    }

    /**
     * 服务是否完全就绪（DB + 规则）。
     */
    public boolean isReady() {
        return databaseReady.get() && rulesReady.get();
    }

    /**
     * 获取各维度就绪详情。
     */
    public ReadinessDetail getDetail() {
        return new ReadinessDetail(databaseReady.get(), rulesReady.get());
    }

    /**
     * 就绪详情值对象。
     */
    public record ReadinessDetail(boolean database, boolean rules) {
        public boolean allReady() {
            return database && rules;
        }
    }
}
