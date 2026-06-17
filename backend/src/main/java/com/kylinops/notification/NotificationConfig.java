package com.kylinops.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 通知中心配置。
 *
 * <p>命名空间:<code>kylinops.notification.*</code></p>
 *
 * <p><b>关键不变量</b>:</p>
 * <ul>
 *   <li>默认 <code>enabled=false</code>(prod 关闭, demo profile 开启)</li>
 *   <li><code>test</code> profile 强制 <code>enabled=false, channels=[]</code>(零外网)</li>
 *   <li>URL/secret 全部走 env 变量,<b>禁止</b>硬编码到 yml</li>
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "kylinops.notification")
public class NotificationConfig {

    /** 全局开关(默认 false) */
    private boolean enabled = false;

    /** dry-run 模式(true 时只 log 不真发) */
    private boolean dryRun = false;

    /** 通道列表 */
    private List<ChannelConfig> channels = List.of();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ChannelConfig {
        /** 通道实例 ID(全局唯一) */
        private String id;
        /** 通道类型(WEBHOOK / FEISHU) */
        private ChannelType type;
        /** 是否启用(默认 true) */
        @Builder.Default
        private boolean enabled = true;
        /**
         * 通道 URL。
         * <ul>
         *   <li>WEBHOOK:通用 webhook 接收端点</li>
         *   <li>FEISHU:飞书机器人 incoming webhook URL</li>
         * </ul>
         * 空字符串视为未配置,dispatcher 自动跳过该通道。
         */
        private String url;
        /** HMAC 签名密钥(WEBHOOK 可选,FEISHU 必填) */
        private String secret;
        /** 连接/读超时(ms,默认 3000) */
        private Integer timeoutMs;

        /** 解析后的超时(回退默认 3000) */
        public int effectiveTimeoutMs() {
            return timeoutMs == null || timeoutMs <= 0 ? 3000 : timeoutMs;
        }
    }
}
