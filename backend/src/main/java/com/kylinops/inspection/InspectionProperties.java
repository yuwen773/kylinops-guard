package com.kylinops.inspection;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 巡检配置:独立于 SafeExecutor 白名单,因为读 + 调度关注点不同。
 *
 * <p>{@code kylinops.inspection.allowed-services} 是 SERVICE 模板校验时可接受的
 * systemd service 名(防止任意服务名落到 journal_log 工具里被滥用为日志采集范围)。
 */
@ConfigurationProperties(prefix = "kylinops.inspection")
public class InspectionProperties {

    /**
     * 允许出现在巡检模板参数里的 systemd service 名集合。
     * 空列表 = 拒绝所有 serviceName(安全默认,部署方按需放开)。
     */
    private List<String> allowedServices = new ArrayList<>();

    public List<String> getAllowedServices() {
        return Collections.unmodifiableList(allowedServices);
    }

    public void setAllowedServices(List<String> allowedServices) {
        this.allowedServices = allowedServices == null
                ? new ArrayList<>()
                : new ArrayList<>(allowedServices);
    }
}