package com.xxx.insurance.ai.workflow.job;

import com.xxx.insurance.ai.workflow.config.WorkflowLifecycleProperties;
import com.xxx.insurance.ai.workflow.mapper.WorkflowExecutionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 回收因 JVM 宕机遗留的 CONFIRMING、RESUMING 瞬时抢占状态。
 *
 * <p>任务不自动调用模型：过期 CONFIRMING 回到 WAITING_CONFIRM，允许用户重提确认；过期
 * RESUMING 回到 RUNNING，允许恢复接口重新从最新 Checkpoint 抢占执行。</p>
 */
@Component
@Profile("local-db")
public class WorkflowLeaseRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(WorkflowLeaseRecoveryJob.class);

    private final WorkflowExecutionMapper workflowExecutionMapper;

    private final WorkflowLifecycleProperties lifecycleProperties;

    /** 创建工作流租约恢复任务。 */
    public WorkflowLeaseRecoveryJob(WorkflowExecutionMapper workflowExecutionMapper,
                                    WorkflowLifecycleProperties lifecycleProperties) {
        this.workflowExecutionMapper = workflowExecutionMapper;
        this.lifecycleProperties = lifecycleProperties;
        this.lifecycleProperties.validate();
    }

    /**
     * 当前 JVM 按 owner 条件续租所有仍有效的执行权；终态或已经换 owner 的实例自然更新不到。
     */
    @Scheduled(
            initialDelayString = "${insurance.ai.workflow.lifecycle.heartbeat-interval:1m}",
            fixedDelayString = "${insurance.ai.workflow.lifecycle.heartbeat-interval:1m}")
    public void renewOwnedLeases() {
        Instant now = Instant.now();
        try {
            int renewed = workflowExecutionMapper.renewOwnedExecutionLeases(
                    lifecycleProperties.getInstanceId(),
                    now.plus(lifecycleProperties.getExecutionLease()),
                    now);
            if (renewed > 0) {
                log.debug("[Workflow] action=lease-heartbeat status=success owner={} renewedCount={}",
                        lifecycleProperties.getInstanceId(), renewed);
            }
        }
        catch (CannotAcquireLockException ex) {
            // 断点或并发收口事务可能暂时持有实例行。放弃本轮续租，下一周期仍按 owner 条件重试；
            // 其他数据库异常继续抛出，避免把连接、SQL 或 Schema 故障误判成可恢复锁竞争。
            log.warn("[Workflow] action=lease-heartbeat status=deferred reason=database-lock-contention owner={}",
                    lifecycleProperties.getInstanceId());
        }
    }

    /** 定期释放已超过 lease_until 的瞬时抢占状态。 */
    @Scheduled(
            initialDelayString = "${insurance.ai.workflow.maintenance.cleanup-initial-delay:1m}",
            fixedDelayString = "${insurance.ai.workflow.maintenance.recovery-interval:30s}")
    @Transactional(rollbackFor = Exception.class)
    public void recoverExpiredClaims() {
        Instant now = Instant.now();
        int confirming = workflowExecutionMapper.recoverExpiredConfirming(now);
        int resuming = workflowExecutionMapper.recoverExpiredResuming(now);
        int conversationLocks = workflowExecutionMapper.deleteExpiredInvalidConversationLocks(now);
        if (confirming > 0 || resuming > 0 || conversationLocks > 0) {
            log.warn("[Workflow] action=lease-recovery status=success confirmingCount={} resumingCount={} "
                            + "conversationLockCount={}",
                    confirming, resuming, conversationLocks);
        }
    }
}
