package com.kylinops.report;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 报告 Repository。
 * <p>
 * 数据库中立：仅使用 Spring Data JPA 派生方法，不绑定 H2 / PostgreSQL 方言。
 * </p>
 */
@Repository
public interface ReportRepository extends JpaRepository<Report, Long> {

    /** 根据 reportId 查询。 */
    Optional<Report> findByReportId(String reportId);

    /**
     * 查询某会话下的报告，按 createdAt DESC。
     * <p>
     * 由调用方提供 {@link Pageable} 以限制返回条数（避免一次会话大量审计导致 OOM）。
     * </p>
     */
    List<Report> findBySessionIdOrderByCreatedAtDesc(String sessionId, Pageable pageable);
}