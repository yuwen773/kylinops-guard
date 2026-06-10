package com.kylinops.report;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 报告 Repository
 */
@Repository
public interface ReportRepository extends JpaRepository<Report, Long> {

    /** 根据 reportId 查询 */
    Optional<Report> findByReportId(String reportId);

    /** 查询某会话的所有报告 */
    List<Report> findBySessionIdOrderByGeneratedAtDesc(String sessionId);

    /** 查询某类型的所有报告 */
    List<Report> findByTypeOrderByGeneratedAtDesc(String type);

    /** 查询某标题包含关键字的报告 */
    List<Report> findByTitleContainingIgnoreCase(String keyword);
}
