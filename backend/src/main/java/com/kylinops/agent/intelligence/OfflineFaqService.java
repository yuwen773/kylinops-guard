package com.kylinops.agent.intelligence;

import com.kylinops.common.enums.IntentType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * LLM 完全不可用时的最后一道兜底。
 *
 * <p>调用顺序（由 HybridIntentService 强制）：
 * <pre>regex → keyword → synonym → LLM → OfflineFaqService → UNKNOWN</pre>
 *
 * <p>FAQ 表只覆盖最常见运维动词，避免误判。</p>
 */
@Component
public class OfflineFaqService {

    private final List<FaqEntry> faqs = List.of(
            new FaqEntry(Pattern.compile("重启.*(nginx|apache|mysql|redis|mariadb|tomcat)"),
                    IntentType.SERVICE_DIAGNOSIS),
            new FaqEntry(Pattern.compile("清.*(缓存|日志|临时|垃圾)"),
                    IntentType.FILE_OPERATION),
            new FaqEntry(Pattern.compile("杀.*(进程|kill)|kill\\s+\\d+"),
                    IntentType.PROCESS_QUERY),
            new FaqEntry(Pattern.compile("为什么.*(慢|卡|挂|异常)"),
                    IntentType.SERVICE_DIAGNOSIS),
            new FaqEntry(Pattern.compile("(网络|网).*(不通|慢|断)"),
                    IntentType.NETWORK_QUERY)
    );

    /**
     * 模糊匹配 FAQ 表。命中时返回带 source=RULE 的 IntentResolution（保持与规则路径一致）。
     */
    public Optional<IntentResolution> fuzzyMatch(String userInput) {
        if (userInput == null || userInput.isBlank()) return Optional.empty();
        for (FaqEntry faq : faqs) {
            if (faq.pattern.matcher(userInput).find()) {
                return Optional.of(IntentResolution.ruleHit(faq.intent));
            }
        }
        return Optional.empty();
    }

    private record FaqEntry(Pattern pattern, IntentType intent) {}
}
