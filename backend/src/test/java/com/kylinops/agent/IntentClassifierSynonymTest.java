package com.kylinops.agent;

import com.kylinops.common.enums.IntentType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IntentClassifierSynonymTest {

    private final IntentClassifier classifier = new IntentClassifier();

    @Test
    void synonym_service_down_matches_service_diagnosis() {
        assertEquals(IntentType.SERVICE_DIAGNOSIS, classifier.classify("服务挂了"));
    }

    @Test
    void synonym_zombie_matches_process_query() {
        assertEquals(IntentType.PROCESS_QUERY, classifier.classify("僵尸进程"));
    }

    @Test
    void synonym_port_listen_matches_network_query() {
        assertEquals(IntentType.NETWORK_QUERY, classifier.classify("端口被占"));
    }

    @Test
    void synonym_db_matches_service_diagnosis() {
        assertEquals(IntentType.SERVICE_DIAGNOSIS, classifier.classify("mysql 挂了"));
    }

    @Test
    void mysql_slow_maps_to_service_diagnosis_via_synonym() {
        // "mysql 慢" 不是命令执行，而是服务异常 → synonym 表中 "mysql" 应命中 SERVICE_DIAGNOSIS
        assertEquals(IntentType.SERVICE_DIAGNOSIS, classifier.classify("mysql 慢"));
    }

    @Test
    void synonym_does_not_override_command_execution_for_dangerous_commands() {
        // COMMAND_EXECUTION 优先级最高，synonym 不得覆盖危险命令
        // 用真正危险的命令（regex 优先匹配 COMMAND_EXECUTION）验证
        assertEquals(IntentType.COMMAND_EXECUTION, classifier.classify("rm -rf /"));
        assertEquals(IntentType.COMMAND_EXECUTION, classifier.classify("chmod -R 777 /"));
    }

    @Test
    void unknown_input_stays_unknown() {
        assertEquals(IntentType.UNKNOWN, classifier.classify("完全无关的随机文本"));
    }
}
