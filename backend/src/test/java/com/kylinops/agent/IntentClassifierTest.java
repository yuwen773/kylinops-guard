package com.kylinops.agent;

import com.kylinops.common.enums.IntentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IntentClassifier 单元测试
 * <p>
 * 验证意图识别器对所有预定义意图类型的正确分类，
 * 包含边界情况、中文输入、英文输入和混杂输入。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IntentClassifier — 意图识别")
class IntentClassifierTest {

    private final IntentClassifier classifier = new IntentClassifier();

    // ==================== SYSTEM_CHECK ====================

    @Test
    @DisplayName("「帮我检查系统健康状态」→ SYSTEM_CHECK")
    void systemCheckIntent() {
        assertThat(classifier.classify("帮我检查系统健康状态"))
                .isEqualTo(IntentType.SYSTEM_CHECK);
    }

    @Test
    @DisplayName("「系统运行正常吗」→ SYSTEM_CHECK")
    void systemRunningNormally() {
        assertThat(classifier.classify("系统运行正常吗"))
                .isEqualTo(IntentType.SYSTEM_CHECK);
    }

    @Test
    @DisplayName("「巡检系统」→ SYSTEM_CHECK")
    void systemInspection() {
        assertThat(classifier.classify("巡检系统"))
                .isEqualTo(IntentType.SYSTEM_CHECK);
    }

    @Test
    @DisplayName("「全面检查系统运行状态」→ SYSTEM_CHECK")
    void fullSystemCheck() {
        assertThat(classifier.classify("全面检查系统运行状态"))
                .isEqualTo(IntentType.SYSTEM_CHECK);
    }

    // ==================== DISK_DIAGNOSIS ====================

    @Test
    @DisplayName("「磁盘快满了」→ DISK_DIAGNOSIS")
    void diskFull() {
        assertThat(classifier.classify("磁盘快满了"))
                .isEqualTo(IntentType.DISK_DIAGNOSIS);
    }

    @Test
    @DisplayName("「帮我看看磁盘使用情况」→ DISK_DIAGNOSIS")
    void diskUsage() {
        assertThat(classifier.classify("帮我看看磁盘使用情况"))
                .isEqualTo(IntentType.DISK_DIAGNOSIS);
    }

    @Test
    @DisplayName("「df -h」→ DISK_DIAGNOSIS")
    void diskCommand() {
        assertThat(classifier.classify("df -h"))
                .isEqualTo(IntentType.DISK_DIAGNOSIS);
    }

    @Test
    @DisplayName("「存储空间不足」→ DISK_DIAGNOSIS")
    void storageFull() {
        assertThat(classifier.classify("存储空间不足"))
                .isEqualTo(IntentType.DISK_DIAGNOSIS);
    }

    // ==================== SERVICE_DIAGNOSIS ====================

    @Test
    @DisplayName("「检查 nginx 服务是否正常」→ SERVICE_DIAGNOSIS")
    void serviceDiagnosis() {
        assertThat(classifier.classify("检查 nginx 服务是否正常"))
                .isEqualTo(IntentType.SERVICE_DIAGNOSIS);
    }

    @Test
    @DisplayName("「重启 mysql 服务」→ SERVICE_DIAGNOSIS")
    void restartService() {
        assertThat(classifier.classify("重启 mysql 服务"))
                .isEqualTo(IntentType.SERVICE_DIAGNOSIS);
    }

    @Test
    @DisplayName("「查看系统服务状态」→ SERVICE_DIAGNOSIS")
    void serviceStatus() {
        assertThat(classifier.classify("查看系统服务状态"))
                .isEqualTo(IntentType.SERVICE_DIAGNOSIS);
    }

    // ==================== PROCESS_QUERY ====================

    @Test
    @DisplayName("「查看进程列表」→ PROCESS_QUERY")
    void processList() {
        assertThat(classifier.classify("查看进程列表"))
                .isEqualTo(IntentType.PROCESS_QUERY);
    }

    @Test
    @DisplayName("「pid=1234 的进程详情」→ PROCESS_QUERY")
    void processDetail() {
        assertThat(classifier.classify("pid=1234 的进程详情"))
                .isEqualTo(IntentType.PROCESS_QUERY);
    }

    @Test
    @DisplayName("「查看系统进程」→ PROCESS_QUERY")
    void systemProcess() {
        assertThat(classifier.classify("查看系统进程"))
                .isEqualTo(IntentType.PROCESS_QUERY);
    }

    // ==================== NETWORK_QUERY ====================

    @Test
    @DisplayName("「查看端口状态」→ NETWORK_QUERY")
    void networkPort() {
        assertThat(classifier.classify("查看端口状态"))
                .isEqualTo(IntentType.NETWORK_QUERY);
    }

    @Test
    @DisplayName("「查看网络连接」→ NETWORK_QUERY")
    void networkConnection() {
        assertThat(classifier.classify("查看网络连接"))
                .isEqualTo(IntentType.NETWORK_QUERY);
    }

    @Test
    @DisplayName("「所有监听端口」→ NETWORK_QUERY")
    void listeningPorts() {
        assertThat(classifier.classify("所有监听端口"))
                .isEqualTo(IntentType.NETWORK_QUERY);
    }

    // ==================== LOG_QUERY ====================

    @Test
    @DisplayName("「查看系统日志」→ LOG_QUERY")
    void systemLog() {
        assertThat(classifier.classify("查看系统日志"))
                .isEqualTo(IntentType.LOG_QUERY);
    }

    @Test
    @DisplayName("「journalctl 最近的错误日志」→ LOG_QUERY")
    void journalctlLog() {
        assertThat(classifier.classify("journalctl 最近的错误日志"))
                .isEqualTo(IntentType.LOG_QUERY);
    }

    // ==================== FILE_OPERATION ====================

    @Test
    @DisplayName("「清理临时文件」→ FILE_OPERATION")
    void cleanTempFiles() {
        assertThat(classifier.classify("清理临时文件"))
                .isEqualTo(IntentType.FILE_OPERATION);
    }

    @Test
    @DisplayName("「清理系统日志」→ FILE_OPERATION")
    void cleanLogs() {
        assertThat(classifier.classify("清理系统日志"))
                .isEqualTo(IntentType.FILE_OPERATION);
    }

    // ==================== COMMAND_EXECUTION ====================

    @Test
    @DisplayName("「执行命令」→ COMMAND_EXECUTION")
    void executeCommand() {
        assertThat(classifier.classify("执行命令"))
                .isEqualTo(IntentType.COMMAND_EXECUTION);
    }

    @Test
    @DisplayName("「rm -rf /」→ COMMAND_EXECUTION（危险命令优先）")
    void dangerousCommand() {
        assertThat(classifier.classify("rm -rf /"))
                .isEqualTo(IntentType.COMMAND_EXECUTION);
    }

    @Test
    @DisplayName("「chmod -R 777 /」→ COMMAND_EXECUTION")
    void chmodCommand() {
        assertThat(classifier.classify("chmod -R 777 /"))
                .isEqualTo(IntentType.COMMAND_EXECUTION);
    }

    // ==================== GENERAL_CHAT ====================

    @Test
    @DisplayName("「你好」→ GENERAL_CHAT")
    void hello() {
        assertThat(classifier.classify("你好"))
                .isEqualTo(IntentType.GENERAL_CHAT);
    }

    @Test
    @DisplayName("「你是谁」→ GENERAL_CHAT")
    void whoAreYou() {
        assertThat(classifier.classify("你是谁"))
                .isEqualTo(IntentType.GENERAL_CHAT);
    }

    @Test
    @DisplayName("「你能做什么」→ GENERAL_CHAT")
    void whatCanYouDo() {
        assertThat(classifier.classify("你能做什么"))
                .isEqualTo(IntentType.GENERAL_CHAT);
    }

    @Test
    @DisplayName("「help」→ GENERAL_CHAT")
    void help() {
        assertThat(classifier.classify("help"))
                .isEqualTo(IntentType.GENERAL_CHAT);
    }

    // ==================== UNKNOWN ====================

    @Test
    @DisplayName("「今天天气怎么样」→ UNKNOWN")
    void weatherQuery() {
        assertThat(classifier.classify("今天天气怎么样"))
                .isEqualTo(IntentType.UNKNOWN);
    }

    @Test
    @DisplayName("空输入 → UNKNOWN")
    void emptyInput() {
        assertThat(classifier.classify(""))
                .isEqualTo(IntentType.UNKNOWN);
    }

    @Test
    @DisplayName("null 输入 → UNKNOWN")
    void nullInput() {
        assertThat(classifier.classify(null))
                .isEqualTo(IntentType.UNKNOWN);
    }

    @Test
    @DisplayName("纯空格输入 → UNKNOWN")
    void blankInput() {
        assertThat(classifier.classify("   "))
                .isEqualTo(IntentType.UNKNOWN);
    }

    @Test
    @DisplayName("无关问题 → UNKNOWN")
    void unrelatedQuestion() {
        assertThat(classifier.classify("1+1等于几"))
                .isEqualTo(IntentType.UNKNOWN);
    }
}
