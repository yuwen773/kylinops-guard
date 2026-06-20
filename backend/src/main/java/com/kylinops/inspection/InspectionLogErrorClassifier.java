package com.kylinops.inspection;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 巡检日志错误分类器(P1-02 Task 3,设计 §6.3)。
 *
 * <p>对 {@code journal_log_tool.data.entries} 的最多 50 行做大小写不敏感的固定词边界
 * 匹配;命中行直接返回原文。命中模式:</p>
 * <ul>
 *   <li>{@code error / failed / failure / fatal / panic / exception}</li>
 *   <li>{@code segfault}</li>
 *   <li>{@code oom / out of memory}</li>
 *   <li>{@code permission denied}</li>
 * </ul>
 *
 * <p><b>已知误报权衡</b>(设计明示接受):{@code "0 errors"} 与
 * {@code "failed login count: 0"} 会被遗漏,但这两个短语缺乏 NLP 上下文很难确定性判定,
 * MVP 不引入 LLM/NLP,后续如需调整必须修改代码并补充语料测试。</p>
 *
 * <p>工具方法,无状态,可静态调用,便于 Service/Controller 共享。</p>
 */
public final class InspectionLogErrorClassifier {

    /** 单次检查的最大行数(设计 §6.3 明确指定)。 */
    public static final int MAX_ENTRIES = 50;

    /**
     * 大小写不敏感的固定词边界模式。{@code "out of memory"} 中含空格,词边界仍然有效。
     *
     * <p>排除两类已知误报(设计 §6.3 接受的误报权衡):</p>
     * <ul>
     *   <li>{@code (?<!\d\s)} — 关键词前不能紧跟「数字 + 空格」,排除 {@code "0 errors"}、
     *       {@code "100 failed"} 等统计计数型上下文</li>
     *   <li>{@code (?!.*\bcount:\s*\d+\s*$)} — 关键词后(同一行剩余部分)不能以
     *       {@code "count: <number>"} 结尾,排除 {@code "failed login count: 0"} 等
     *       仪表盘报告形式</li>
     * </ul>
     */
    private static final Pattern ERROR_PATTERN = Pattern.compile(
            "(?<!\\d\\s)(?!.*\\bcount:\\s*\\d+\\s*$)\\b(error|failed|failure|fatal|panic|exception|segfault|oom|out of memory|permission denied)\\b",
            Pattern.CASE_INSENSITIVE);

    private InspectionLogErrorClassifier() {
        // 工具类
    }

    /**
     * 对 entries 做错误模式匹配,返回原始命中行(保持顺序)。
     *
     * @param entries journal_log_tool 返回的日志行列表(可空)
     * @return 命中错误的原始行列表;空 entries / null / 无命中 → 空列表
     */
    public static List<String> classify(List<String> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        int upperBound = Math.min(entries.size(), MAX_ENTRIES);
        List<String> hits = new ArrayList<>();
        for (int i = 0; i < upperBound; i++) {
            String line = entries.get(i);
            if (line == null) {
                continue;
            }
            if (ERROR_PATTERN.matcher(line).find()) {
                hits.add(line);
            }
        }
        return hits;
    }
}