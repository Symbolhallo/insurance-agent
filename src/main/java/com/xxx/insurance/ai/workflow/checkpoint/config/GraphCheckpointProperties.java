package com.xxx.insurance.ai.workflow.checkpoint.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Graph Checkpoint 持久化与保留策略配置。
 */
@Validated
@ConfigurationProperties(prefix = "insurance.ai.workflow.checkpoint")
public class GraphCheckpointProperties {

    /** ACTIVE/RUNNING 及 FAILED 线程用于恢复和排障的默认保留时间。 */
    private Duration activeRetention = Duration.ofDays(7);

    /** COMPLETED/RELEASED 线程完成后的默认保留时间。 */
    private Duration completedRetention = Duration.ofHours(24);

    private int stateSchemaVersion = 1;

    private int maxWriteRetries = 5;

    public Duration getActiveRetention() {
        return activeRetention;
    }

    public void setActiveRetention(Duration activeRetention) {
        this.activeRetention = activeRetention;
    }

    public Duration getCompletedRetention() {
        return completedRetention;
    }

    public void setCompletedRetention(Duration completedRetention) {
        this.completedRetention = completedRetention;
    }

    public int getStateSchemaVersion() {
        return stateSchemaVersion;
    }

    public void setStateSchemaVersion(int stateSchemaVersion) {
        this.stateSchemaVersion = stateSchemaVersion;
    }

    public int getMaxWriteRetries() {
        return maxWriteRetries;
    }

    public void setMaxWriteRetries(int maxWriteRetries) {
        this.maxWriteRetries = maxWriteRetries;
    }

    public void validate() {
        if (activeRetention == null || activeRetention.isNegative() || activeRetention.isZero()) {
            throw new IllegalArgumentException("activeRetention must be positive");
        }
        if (completedRetention == null || completedRetention.isNegative() || completedRetention.isZero()) {
            throw new IllegalArgumentException("completedRetention must be positive");
        }
        if (stateSchemaVersion < 1) {
            throw new IllegalArgumentException("stateSchemaVersion must be greater than zero");
        }
        if (maxWriteRetries < 1 || maxWriteRetries > 20) {
            throw new IllegalArgumentException("maxWriteRetries must be between 1 and 20");
        }
    }
}
