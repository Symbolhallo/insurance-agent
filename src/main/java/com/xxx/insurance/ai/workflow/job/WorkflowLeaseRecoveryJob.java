package com.xxx.insurance.ai.workflow.job;

import com.xxx.insurance.ai.workflow.mapper.WorkflowExecutionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
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

    /** 创建工作流租约恢复任务。 */
    public WorkflowLeaseRecoveryJob(WorkflowExecutionMapper workflowExecutionMapper) {
        this.workflowExecutionMapper = workflowExecutionMapper;
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
        if (confirming > 0 || resuming > 0) {
            log.warn("[Workflow] action=lease-recovery status=success confirmingCount={} resumingCount={}",
                    confirming, resuming);
        }
    }
}
