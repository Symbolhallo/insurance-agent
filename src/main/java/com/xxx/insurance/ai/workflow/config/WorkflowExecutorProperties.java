package com.xxx.insurance.ai.workflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 工作流 DAG、SSE、Token 刷新和维护调度器的容量配置。 */
@ConfigurationProperties(prefix = "insurance.ai.workflow.executor")
public class WorkflowExecutorProperties {

    private int dagCorePoolSize = 2;
    private int dagMaxPoolSize = 4;
    private int dagQueueCapacity = 32;
    private int sseCorePoolSize = 2;
    private int sseMaxPoolSize = 8;
    private int tokenFlushPoolSize = 2;
    private int maintenancePoolSize = 2;
    private int shutdownAwaitSeconds = 30;

    public int getDagCorePoolSize() {
        return dagCorePoolSize;
    }

    public void setDagCorePoolSize(int dagCorePoolSize) {
        this.dagCorePoolSize = dagCorePoolSize;
    }

    public int getDagMaxPoolSize() {
        return dagMaxPoolSize;
    }

    public void setDagMaxPoolSize(int dagMaxPoolSize) {
        this.dagMaxPoolSize = dagMaxPoolSize;
    }

    public int getDagQueueCapacity() {
        return dagQueueCapacity;
    }

    public void setDagQueueCapacity(int dagQueueCapacity) {
        this.dagQueueCapacity = dagQueueCapacity;
    }

    public int getSseCorePoolSize() {
        return sseCorePoolSize;
    }

    public void setSseCorePoolSize(int sseCorePoolSize) {
        this.sseCorePoolSize = sseCorePoolSize;
    }

    public int getSseMaxPoolSize() {
        return sseMaxPoolSize;
    }

    public void setSseMaxPoolSize(int sseMaxPoolSize) {
        this.sseMaxPoolSize = sseMaxPoolSize;
    }

    public int getTokenFlushPoolSize() {
        return tokenFlushPoolSize;
    }

    public void setTokenFlushPoolSize(int tokenFlushPoolSize) {
        this.tokenFlushPoolSize = tokenFlushPoolSize;
    }

    public int getMaintenancePoolSize() {
        return maintenancePoolSize;
    }

    public void setMaintenancePoolSize(int maintenancePoolSize) {
        this.maintenancePoolSize = maintenancePoolSize;
    }

    public int getShutdownAwaitSeconds() {
        return shutdownAwaitSeconds;
    }

    public void setShutdownAwaitSeconds(int shutdownAwaitSeconds) {
        this.shutdownAwaitSeconds = shutdownAwaitSeconds;
    }

    /** 启动时拒绝无效容量，避免线程池在首个请求到来后才失败。 */
    public void validate() {
        validatePool(dagCorePoolSize, dagMaxPoolSize, "dag");
        validatePool(sseCorePoolSize, sseMaxPoolSize, "sse");
        if (dagQueueCapacity < 0) {
            throw new IllegalArgumentException("dagQueueCapacity must not be negative");
        }
        if (tokenFlushPoolSize <= 0 || maintenancePoolSize <= 0 || shutdownAwaitSeconds <= 0) {
            throw new IllegalArgumentException("scheduler sizes and shutdownAwaitSeconds must be positive");
        }
    }

    private void validatePool(int corePoolSize, int maxPoolSize, String name) {
        if (corePoolSize <= 0 || maxPoolSize < corePoolSize) {
            throw new IllegalArgumentException(name + " pool sizes are invalid");
        }
    }
}
