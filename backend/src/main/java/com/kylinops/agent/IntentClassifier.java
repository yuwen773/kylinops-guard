package com.kylinops.agent;

import com.kylinops.common.enums.IntentType;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 意图识别器
 * <p>
 * 将用户自然语言输入映射为预定义的 {@link IntentType}。
 * P0 采用规则优先策略（关键词 + 正则匹配），LLM 可选作为后备。
 * </p>
 *
 * <h3>匹配策略</h3>
 * <ol>
 *   <li>按优先级降序遍历规则列表</li>
 *   <li>对每条规则，尝试正则匹配和关键词匹配</li>
 *   <li>任一命中即返回对应意图</li>
 *   <li>全部未命中返回 {@link IntentType#UNKNOWN}</li>
 * </ol>
 *
 * <h3>安全约束</h3>
 * <ul>
 *   <li>COMMAND_EXECUTION 中的危险命令由外部 PromptInjectionDetector 处理</li>
 *   <li>IntentClassifier 只做意图分类，不做安全决策</li>
 * </ul>
 */
@Slf4j
@Component
public class IntentClassifier {

    /** 规则列表（按优先级降序排列） */
    private final List<IntentRule> rules = new ArrayList<>();

    // ==================== Synonym 扩展（P0 Fix-03） ====================
    // 注意：synonym 不覆盖 COMMAND_EXECUTION（regex 已优先匹配危险命令）。
    // 字段级实例初始化块，与 initRules() 是两条独立路径，互斥。
    private final Map<IntentType, List<String>> synonymMap = new HashMap<>();

    {
        synonymMap.put(IntentType.DISK_DIAGNOSIS,
                List.of("磁盘空间", "硬盘满了", "空间不足", "清理磁盘", "存储满了"));
        synonymMap.put(IntentType.SERVICE_DIAGNOSIS,
                List.of("服务挂了", "服务异常", "服务正常吗", "db", "mysql", "redis",
                        "mariadb", "postgresql"));
        synonymMap.put(IntentType.PROCESS_QUERY,
                List.of("卡死", "僵死", "僵死进程", "僵尸进程", "zombie", "进程僵死"));
        synonymMap.put(IntentType.NETWORK_QUERY,
                List.of("端口被占", "端口占用", "端口冲突", "listen"));
        synonymMap.put(IntentType.LOG_QUERY,
                List.of("查日志", "错误日志", "应用日志", "系统日志"));
    }

    /**
     * 对外暴露：测试 / 扩展用。
     */
    public void addSynonym(IntentType intent, String... keywords) {
        synonymMap.computeIfAbsent(intent, k -> new ArrayList<>())
                .addAll(Arrays.asList(keywords));
    }

    public IntentClassifier() {
        initRules();
    }

    /**
     * 初始化意图规则
     * <p>
     * 规则按优先级从高到低添加（高优先级先匹配）。
     * </p>
     */
    private void initRules() {
        // COMMAND_EXECUTION — 优先级 15（最高，危险命令需优先识别）
        // 注意：`运行` 必须是组合词（运行命令/运行脚本），单独出现不触发此意图
        rules.add(new IntentRule(IntentType.COMMAND_EXECUTION,
                Pattern.compile("执行|运行.*(命令|脚本|程序)|rm -rf|chmod|dd|mkfs|fdisk|format|格式化|删除.*(根|系统)"),
                List.of("rm -rf", "chmod", "dd if=", ":(){"), 15));

        // SYSTEM_CHECK — 优先级 10
        rules.add(new IntentRule(IntentType.SYSTEM_CHECK,
                Pattern.compile("健康|系统状态|巡检|检查系统|检查.*运行|运行.*正常|整体.*状态|全面.*检查|系统巡检|运行状态"),
                List.of("系统检查", "健康检查", "系统状态"), 10));

        // DISK_DIAGNOSIS — 优先级 10
        // 注意：不含 `清理.*日志` / `清理.*空间` — 清理类操作归属 FILE_OPERATION
        rules.add(new IntentRule(IntentType.DISK_DIAGNOSIS,
                Pattern.compile("磁盘|硬盘|存储|空间|df|du|磁盘满|磁盘使用|存储.*使用|磁盘.*空间|空间不足|磁盘.*分析"),
                List.of("磁盘", "硬盘", "存储"), 10));

        // SERVICE_DIAGNOSIS — 优先级 10
        rules.add(new IntentRule(IntentType.SERVICE_DIAGNOSIS,
                Pattern.compile("服务|nginx|mysql|redis|重启|启动|停止.*服务|systemctl|服务.*状态|服务.*正常|服务.*检查"),
                List.of("服务"), 10));

        // PROCESS_QUERY — 优先级 10
        rules.add(new IntentRule(IntentType.PROCESS_QUERY,
                Pattern.compile("进程|ps|top|pid|线程|进程列表|进程.*详情|占用.*高"),
                List.of("进程"), 10));

        // NETWORK_QUERY — 优先级 10
        rules.add(new IntentRule(IntentType.NETWORK_QUERY,
                Pattern.compile("端口|网络|监听|ss|netstat|连接|网络连接|端口.*状态|监听.*端口"),
                List.of("端口", "网络"), 10));

        // FILE_OPERATION — 优先级 10（必须位于 LOG_QUERY 之前，确保"清理日志"等操作优先匹配）
        rules.add(new IntentRule(IntentType.FILE_OPERATION,
                Pattern.compile("清理|删除.*文件|截断|清空|rm|清理.*日志|删除.*目录|清理.*缓存|清理.*文件|清理.*空间"),
                List.of("清理文件", "删除文件", "截断日志"), 10));

        // LOG_QUERY — 优先级 10
        rules.add(new IntentRule(IntentType.LOG_QUERY,
                Pattern.compile("日志|log|journalctl|查看日志|应用日志|错误日志"),
                List.of("日志", "log"), 10));

        // GENERAL_CHAT — 优先级 0（最低，兜底匹配）
        rules.add(new IntentRule(IntentType.GENERAL_CHAT,
                Pattern.compile("你好|^嗨$|你是谁|能做什么|帮助|hello|^hi$|功能|介绍"),
                List.of("你好", "help", "功能"), 0));

        log.info("IntentClassifier 初始化完成: 共 {} 条规则", rules.size());
    }

    /**
     * 将用户输入分类为预定义意图。
     *
     * @param userInput 用户自然语言输入
     * @return 识别到的意图（永远不会返回 null）
     */
    public IntentType classify(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return IntentType.UNKNOWN;
        }

        String normalized = userInput.trim();

        // 按优先级降序匹配（规则已按优先级降序排列）
        for (IntentRule rule : rules) {
            // 正则匹配
            if (rule.getPattern().matcher(normalized).find()) {
                log.debug("意图识别 [正则匹配]: input='{}', intent={}, priority={}",
                        truncate(normalized, 40), rule.getIntent(), rule.getPriority());
                return rule.getIntent();
            }

            // 关键词匹配（补充匹配）
            if (rule.getKeywords() != null && matchKeywords(normalized, rule.getKeywords())) {
                log.debug("意图识别 [关键词匹配]: input='{}', intent={}, priority={}",
                        truncate(normalized, 40), rule.getIntent(), rule.getPriority());
                return rule.getIntent();
            }
        }

        // synonym 兜底（仅 regex/keyword 都未命中时；不覆盖 COMMAND_EXECUTION，
        // 因为 COMMAND_EXECUTION 是 priority=15 且 regex 已在第一轮匹配）
        for (Map.Entry<IntentType, List<String>> e : synonymMap.entrySet()) {
            if (matchKeywords(normalized, e.getValue())) {
                log.debug("意图识别 [synonym 匹配]: input='{}', intent={}",
                        truncate(normalized, 40), e.getKey());
                return e.getKey();
            }
        }

        log.debug("意图识别: input='{}' → UNKNOWN", truncate(normalized, 40));
        return IntentType.UNKNOWN;
    }

    /**
     * 检查输入是否包含关键词列表中的任一关键词。
     */
    private boolean matchKeywords(String input, List<String> keywords) {
        String lowerInput = input.toLowerCase();
        return keywords.stream().anyMatch(kw -> lowerInput.contains(kw.toLowerCase()));
    }

    /**
     * 截断长字符串用于日志。
     */
    private String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() <= maxLen ? str : str.substring(0, maxLen) + "...";
    }

    // ==================== 内部类型 ====================

    /**
     * 意图匹配规则
     */
    @Getter
    public static class IntentRule {
        private final IntentType intent;
        private final Pattern pattern;
        private final List<String> keywords;
        private final int priority;

        public IntentRule(IntentType intent, Pattern pattern, List<String> keywords, int priority) {
            this.intent = intent;
            this.pattern = pattern;
            this.keywords = keywords;
            this.priority = priority;
        }
    }
}
