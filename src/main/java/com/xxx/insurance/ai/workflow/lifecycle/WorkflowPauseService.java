package com.xxx.insurance.ai.workflow.lifecycle;

import com.xxx.insurance.ai.workflow.config.WorkflowLifecycleProperties;
import com.xxx.insurance.ai.workflow.mapper.WorkflowExecutionMapper;
import com.xxx.insurance.ai.workflow.sse.service.LocalDbWorkflowSseEventService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/** 原子提交人工确认暂停状态、步骤审计、会话锁和可重放事件。 */
@Service
@Profile("local-db")
public class WorkflowPauseService {

    private static final String STATUS_WAITING_CONFIRM = "WAITING_CONFIRM";

    private final WorkflowExecutionMapper executionMapper;
    private final LocalDbWorkflowSseEventService eventService;
    private final WorkflowLifecycleProperties lifecycleProperties;

    /** 创建人工暂停事务服务。 */
    public WorkflowPauseService(WorkflowExecutionMapper executionMapper,
                                LocalDbWorkflowSseEventService eventService,
                                WorkflowLifecycleProperties lifecycleProperties) {
        this.executionMapper = executionMapper;
        this.eventService = eventService;
        this.lifecycleProperties = lifecycleProperties;
    }

    /**
     * 只有当前 owner/token/lease 能把运行实例暂停；任一步失败都会回滚，避免状态与确认事件分离。
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
