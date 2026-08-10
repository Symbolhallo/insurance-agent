package com.xxx.insurance.ai.workflow.service;

import com.xxx.insurance.ai.workflow.checkpoint.OceanBaseCheckpointSaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 定期清理超过保留期的 Graph Checkpoint 和 SSE 重放事件。
 *
 * <p>两类数据分别清理并独立捕获异常，避免某一张表临时不可用时阻塞另一类过期数据回收。
 * 聊天长期记忆和业务审计不属于本任务清理范围。</p>
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

    /** 按配置周期执行过期数据清理，首次启动延迟一分钟以避开应用初始化高峰。 */
    @Scheduled(
            initialDelayString = "${insurance.ai.workflow.maintenance.cleanup-initial-delay:1m}",
            fixedDelayString = "${insurance.ai.workflow.maintenance.cleanup-interval:1h}")
    public void cleanExpiredData() {
        Instant now = Instant.now();
        try {
            checkpointSaver.purgeExpired(now);
        }
        catch (Exception ex) {
            log.error("[Memory] type=checkpoint action=scheduled-purge status=failed", ex);
        }
        try {
            sseEventService.purgeExpiredEvents(now);
        }
        catch (Exception ex) {
            log.error("[Workflow] action=scheduled-sse-event-purge status=failed", ex);
        }
    }
}
