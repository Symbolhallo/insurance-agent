package com.xxx.insurance.ai.workflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 工作流 SSE 连接超时与事件重放保留策略。
 */
@ConfigurationProperties(prefix = "insurance.ai.workflow.sse")
public record WorkflowSseProperties(
        Duration connectionTimeout,
        Duration eventRetention,
        Duration databasePollInterval) {

    /** 为未配置属性提供生产验证阶段的保守默认值。 */
    public WorkflowSseProperties {
        connectionTimeout = connectionTimeout == null ? Duration.ofMinutes(5) : connectionTimeout;
        eventRetention = eventRetention == null ? Duration.ofDays(7) : eventRetention;
        databasePollInterval = databasePollInterval == null ? Duration.ofMillis(500) : databasePollInterval;
        if (databasePollInterval.isNegative() || databasePollInterval.isZero()) {
            throw new IllegalArgumentException("databasePollInterval must be positive");
        }
    }
}
