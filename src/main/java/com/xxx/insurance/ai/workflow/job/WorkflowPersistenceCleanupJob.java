package com.xxx.insurance.ai.workflow.job;

import com.xxx.insurance.ai.workflow.checkpoint.OceanBaseCheckpointSaver;
import com.xxx.insurance.ai.workflow.service.LocalDbWorkflowSseEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 按不同周期清理超过保留期的 Graph Checkpoint 和 SSE 重放事件。
 *
 * <p>Checkpoint 数据量较大，保持小时级清理；SSE 事件默认10分钟过期并按30秒扫描，确保
 * 到期数据及时从 OceanBase 物理删除。两类数据独立调度，聊天长期记忆和业务审计不在
 * 本任务清理范围内。</p>
 */
@Component
@Profile("local-db")
public class WorkflowPersistenceCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(WorkflowPersistenceCleanupJob.class);

    private final OceanBaseCheckpointSaver checkpointSaver;

    private final LocalDbWorkflowSseEventService sseEventService;

    /** 创建工作流持久化清理任务。 */
    public WorkflowPersistenceCleanupJob(OceanBaseCheckpointSaver checkpointSaver,
                                         LocalDbWorkflowSseEventService sseEventService) {
        this.checkpointSaver = checkpointSaver;
        this.sseEventService = sseEventService;
    }

    /** 按小时级配置清理过期 Checkpoint，避免高频扫描体量较大的状态快照。 */
    @Scheduled(
            initialDelayString = "${insurance.ai.workflow.maintenance.cleanup-initial-delay:1m}",
            fixedDelayString = "${insurance.ai.workflow.maintenance.cleanup-interval:1h}")
    public void cleanExpiredCheckpoints() {
        try {
            checkpointSaver.purgeExpired(Instant.now());
        }
        catch (Exception ex) {
            log.error("[Memory] type=checkpoint action=scheduled-purge status=failed", ex);
        }
    }

    /**
     * 高频删除已达到 expire_at 的 SSE 事件；默认每30秒执行，因此10分钟到期后最多约30秒物理删除。
     */
    @Scheduled(
            initialDelayString = "${insurance.ai.workflow.maintenance.sse-cleanup-initial-delay:30s}",
            fixedDelayString = "${insurance.ai.workflow.maintenance.sse-cleanup-interval:30s}")
    public void cleanExpiredSseEvents() {
        try {
            sseEventService.purgeExpiredEvents(Instant.now());
        }
        catch (Exception ex) {
            log.error("[Workflow] action=scheduled-sse-event-purge status=failed", ex);
        }
    }
}
