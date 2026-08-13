package com.xxx.insurance.ai.workflow.lifecycle;

import com.xxx.insurance.ai.workflow.config.WorkflowLifecycleProperties;
import com.xxx.insurance.ai.workflow.mapper.WorkflowExecutionMapper;
import com.xxx.insurance.ai.workflow.sse.service.LocalDbWorkflowSseEventService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/** 原子提交人工确认暂停状态、步骤审计、会话锁和可重放 HUMAN_CONFIRM Outbox 事件。 */
@Service
@Profile("local-db")
public class WorkflowPauseService {

    private static final String STATUS_WAITING_CONFIRM = "WAITING_CONFIRM";

    private final WorkflowExecutionMapper executionMapper;
    private final LocalDbWorkflowSseEventService eventService;
    private final WorkflowLifecycleProperties lifecycleProperties;

    /** 创建人工暂停事务服务，组合状态机 Mapper、SSE 事实表和等待确认租约配置。 */
    public WorkflowPauseService(WorkflowExecutionMapper executionMapper,
                                LocalDbWorkflowSseEventService eventService,
                                WorkflowLifecycleProperties lifecycleProperties) {
        this.executionMapper = executionMapper;
        this.eventService = eventService;
        this.lifecycleProperties = lifecycleProperties;
    }

    /**
     * 将 Graph 中断原子转换为可恢复的人工确认状态。依次用 owner、fencing token 和有效 lease 校验并
     * 更新人工确认步骤，迁移实例到 WAITING_CONFIRM，延长 conversation 独占锁到人工确认期限，最后在
     * 同一事务写入带候选信息的 HUMAN_CONFIRM 事实事件。任一步 CAS 或事件写入失败都会整体回滚，避免
     * 出现实例已暂停但前端无可重放确认事件，或事件存在但 Checkpoint 不允许确认的分裂状态；实际网络
     * 发送发生在事务提交后的 flush/Poller 中。
     */
    @Transactional(rollbackFor = Exception.class)
    public void pauseForProductConfirmation(String workflowInstanceId,
                                            String conversationId,
                                            long executionFenceToken,
                                            String workflowStepId,
                                            String responseJson,
                                            String recallResultJson,
                                            String node,
                                            Map<String, Object> eventData,
                                            Instant waitingAt) {
        if (executionMapper.updateStepWaitingConfirm(
                workflowStepId, recallResultJson, lifecycleProperties.getInstanceId(),
                executionFenceToken, waitingAt) != 1) {
            throw new IllegalStateException("Workflow execution lease was lost before pausing step");
        }
        if (executionMapper.updateInstanceStatus(
                workflowInstanceId, STATUS_WAITING_CONFIRM, responseJson,
                lifecycleProperties.getInstanceId(), executionFenceToken, waitingAt) != 1) {
            throw new IllegalStateException("Workflow execution lease was lost before human confirmation");
        }
        if (executionMapper.renewConversationLock(
                workflowInstanceId, executionFenceToken,
                waitingAt.plus(lifecycleProperties.getWaitingConfirmLease()), waitingAt) != 1) {
            throw new IllegalStateException("Workflow conversation lock was lost before human confirmation");
        }
        eventService.persistWaitingConfirmEvent(
                workflowInstanceId, conversationId, executionFenceToken, node, eventData);
    }
}
