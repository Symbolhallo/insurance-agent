package com.xxx.insurance.ai.workflow.sse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 工作流 SSE 连接超时与事件重放保留策略。
 */
@ConfigurationProperties(prefix = "insurance.ai.workflow.sse")
public record WorkflowSseProperties(
        Duration connectionTimeout,
        Duration eventRetention,
        Duration databasePollInterval,
        Duration tokenBatchMaxDelay,
        int tokenBatchMaxCharacters) {

    private static final Duration MIN_ALLOWED_TOKEN_BATCH_DELAY = Duration.ofMillis(1);

    private static final Duration MAX_ALLOWED_TOKEN_BATCH_DELAY = Duration.ofSeconds(1);

    private static final int DEFAULT_TOKEN_BATCH_MAX_CHARACTERS = 128;

    /** 为未配置属性提供生产验证阶段的保守默认值。 */
    public WorkflowSseProperties {
        connectionTimeout = connectionTimeout == null ? Duration.ofMinutes(5) : connectionTimeout;
        eventRetention = eventRetention == null ? Duration.ofMinutes(10) : eventRetention;
        databasePollInterval = databasePollInterval == null ? Duration.ofMillis(500) : databasePollInterval;
        tokenBatchMaxDelay = tokenBatchMaxDelay == null ? Duration.ofMillis(80) : tokenBatchMaxDelay;
        tokenBatchMaxCharacters = tokenBatchMaxCharacters <= 0
                ? DEFAULT_TOKEN_BATCH_MAX_CHARACTERS
                : tokenBatchMaxCharacters;
        if (databasePollInterval.isNegative() || databasePollInterval.isZero()) {
            throw new IllegalArgumentException("databasePollInterval must be positive");
        }
        if (tokenBatchMaxDelay.compareTo(MIN_ALLOWED_TOKEN_BATCH_DELAY) < 0
                || tokenBatchMaxDelay.compareTo(MAX_ALLOWED_TOKEN_BATCH_DELAY) > 0) {
            throw new IllegalArgumentException("tokenBatchMaxDelay must be between 1ms and 1s");
        }
    }
}
