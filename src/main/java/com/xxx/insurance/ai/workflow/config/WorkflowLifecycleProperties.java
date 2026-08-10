package com.xxx.insurance.ai.workflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.UUID;

/**
 * 工作流执行权租约和会话并发控制配置。
 */
@ConfigurationProperties(prefix = "insurance.ai.workflow.lifecycle")
public class WorkflowLifecycleProperties {

    private String instanceId = "insurance-agent-" + UUID.randomUUID().toString().replace("-", "");

    private Duration executionLease = Duration.ofMinutes(15);

    private Duration claimLease = Duration.ofMinutes(2);

    private Duration waitingConfirmLease = Duration.ofHours(24);

    /** 当前 JVM 的执行者编号，写入数据库后用于识别租约持有者。 */
    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    /** 普通 Graph 执行和恢复执行的租约时长。 */
    public Duration getExecutionLease() {
        return executionLease;
    }

    public void setExecutionLease(Duration executionLease) {
        this.executionLease = executionLease;
    }

    /** CONFIRMING、RESUMING 瞬时抢占状态的租约时长。 */
    public Duration getClaimLease() {
        return claimLease;
    }

    public void setClaimLease(Duration claimLease) {
        this.claimLease = claimLease;
    }

    /** 等待人工确认期间保留 conversation 顶层执行权的时长。 */
    public Duration getWaitingConfirmLease() {
        return waitingConfirmLease;
    }

    public void setWaitingConfirmLease(Duration waitingConfirmLease) {
        this.waitingConfirmLease = waitingConfirmLease;
    }
}
