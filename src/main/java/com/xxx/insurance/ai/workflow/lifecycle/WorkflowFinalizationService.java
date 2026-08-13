package com.xxx.insurance.ai.workflow.lifecycle;

import com.xxx.insurance.ai.memory.model.AgentInvocationRecord;
import com.xxx.insurance.ai.memory.model.AgentMemoryExchange;
import com.xxx.insurance.ai.memory.service.AgentMemoryService;
import com.xxx.insurance.ai.workflow.checkpoint.OceanBaseCheckpointSaver;
import com.xxx.insurance.ai.workflow.config.WorkflowLifecycleProperties;
import com.xxx.insurance.ai.workflow.mapper.WorkflowExecutionMapper;
import com.xxx.insurance.ai.workflow.model.MainWorkflowResponse;
import com.xxx.insurance.ai.workflow.model.WorkflowNodeDefinition;
import com.xxx.insurance.ai.workflow.sse.model.WorkflowSseEventType;
import com.xxx.insurance.ai.workflow.sse.service.LocalDbWorkflowSseEventService;
import com.xxx.insurance.common.util.TraceIdUtil;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 工作流最终收口事务边界。
 *
 * <p>实例终态、最终 Memory、步骤状态、Checkpoint 状态和终态 SSE Outbox 事件在同一
 * OceanBase 事务中提交。以 {@code wfa-{workflowInstanceId}} 作为最终调用幂等键，且先通过
 * 实例状态条件更新获得收口权，从而避免崩溃重试产生重复会话记录。</p>
 */
@Service
@Profile("local-db")
public class WorkflowFinalizationService {

    private final WorkflowExecutionMapper workflowExecutionMapper;

    private final AgentMemoryService agentMemoryService;

    private final OceanBaseCheckpointSaver checkpointSaver;

    private final LocalDbWorkflowSseEventService sseEventService;

    private final WorkflowLifecycleProperties lifecycleProperties;

    /** 创建工作流收口事务服务。 */
    public WorkflowFinalizationService(WorkflowExecutionMapper workflowExecutionMapper,
                                       AgentMemoryService agentMemoryService,
                                       OceanBaseCheckpointSaver checkpointSaver,
                                       LocalDbWorkflowSseEventService sseEventService,
                                       WorkflowLifecycleProperties lifecycleProperties) {
        this.workflowExecutionMapper = workflowExecutionMapper;
        this.agentMemoryService = agentMemoryService;
        this.checkpointSaver = checkpointSaver;
        this.sseEventService = sseEventService;
        this.lifecycleProperties = lifecycleProperties;
    }

    /**
     * 原子提交正常业务终态。返回 false 表示其他执行者已经提交终态，本次调用不再产生副作用。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean complete(MainWorkflowResponse response,
                            String outputJson,
                            String modelName,
                            long executionFenceToken) {
        Instant endedAt = response.endedAt();
        int finalized = workflowExecutionMapper.finalizeInstance(
                response.workflowInstanceId(), response.status(), outputJson, response.errorMessage(),
                lifecycleProperties.getInstanceId(), executionFenceToken, endedAt);
        if (finalized == 0) {
            return false;
        }

        // 最终收口 1：固定 invocationId，并与终态更新处于同一事务，重试不会重复写 Memory。
        saveFinalConversation(response, modelName);
        workflowExecutionMapper.skipPendingSteps(response.workflowInstanceId(), endedAt);
        checkpointSaver.markWorkflowCompleted(response.workflowInstanceId(), executionFenceToken);
        // 最终收口 2：COMPLETE 先作为事实事件落库，提交后由 SSE Poller 投递，具备 Outbox 语义。
        sseEventService.persistTransactionalEvent(
                response.workflowInstanceId(), response.conversationId(), executionFenceToken,
                WorkflowSseEventType.COMPLETE, null,
                Map.of(
                        "status", response.status(),
                        "finalAnswer", response.finalAnswer(),
                        "durationMs", response.durationMs()));
        workflowExecutionMapper.deleteConversationLock(response.workflowInstanceId());
        return true;
    }

    /**
     * 原子提交系统失败。终态条件更新失败时说明正常收口已经完成，迟到异常会被安全忽略。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean fail(String workflowInstanceId,
                        String conversationId,
                        String errorMessage,
                        long executionFenceToken,
                        Instant endedAt) {
        if (workflowExecutionMapper.failInstanceIfNonTerminal(
                workflowInstanceId, errorMessage, lifecycleProperties.getInstanceId(),
                executionFenceToken, endedAt) == 0) {
            return false;
        }
        workflowExecutionMapper.skipPendingSteps(workflowInstanceId, endedAt);
        checkpointSaver.markWorkflowFailed(workflowInstanceId, executionFenceToken);
        sseEventService.persistTransactionalEvent(
                workflowInstanceId, conversationId, executionFenceToken,
                WorkflowSseEventType.ERROR, null,
                Map.of("status", "FAILED", "message", "主工作流执行失败，请稍后重试或联系人工支持"));
        workflowExecutionMapper.deleteConversationLock(workflowInstanceId);
        return true;
    }

    /**
     * 事务提交后尽快投递已落库终态事件；投递异常由事件服务内部吞吐，定时 Poller 继续补偿。
     */
    public void flushEvents(String workflowInstanceId) {
        sseEventService.flushPersistedEvents(workflowInstanceId);
    }

    /** 将主工作流最终问答写入短期记忆、长期记忆和调用审计。 */
    private void saveFinalConversation(MainWorkflowResponse response, String modelName) {
        if (!agentMemoryService.isEnabled()) {
            return;
        }
        String invocationId = "wfa-" + response.workflowInstanceId();
        AgentInvocationRecord invocationRecord = new AgentInvocationRecord(
                invocationId,
                response.conversationId(),
                "main-workflow",
                TraceIdUtil.currentTraceId(),
                response.workflowInstanceId(),
                response.workflowStepIds().get(WorkflowNodeDefinition.SUMMARY.code()),
                "openai-compatible",
                modelName,
                "mock-user",
                "mock-customer",
                "mock-operator",
                response.originalQuestion(),
                response.finalAnswer(),
                response.durationMs(),
                response.finalAnswer() == null ? 0 : response.finalAnswer().length(),
                null,
                List.of(),
                "SUCCESS",
                null,
                null,
                response.endedAt());
        agentMemoryService.saveSuccessfulExchange(
                new AgentMemoryExchange(
                        response.conversationId(), invocationId, "main-workflow",
                        new UserMessage(response.originalQuestion()),
                        new AssistantMessage(response.finalAnswer()), response.endedAt()),
                invocationRecord);
    }
}
