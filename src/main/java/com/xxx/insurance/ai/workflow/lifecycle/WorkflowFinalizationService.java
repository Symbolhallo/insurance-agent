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

    /** 创建工作流收口事务服务，组合实例状态机、最终 Memory、Checkpoint、SSE Outbox 和租约配置。 */
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
     * 原子提交正常业务终态。先通过 owner、fencing token、有效 lease 和非终态条件更新取得唯一收口权；
     * 成功后以稳定的 wfa-{workflowInstanceId} invocationId 写最终短期/长期记忆和调用审计，跳过剩余步骤，
     * 将主图及任务 Checkpoint 标为完成，写入 COMPLETE Outbox 事件，并释放 conversation 锁。所有操作共享
     * 一个 OceanBase 事务；返回 false 表示其他执行者已收口，本次不写 Memory、Checkpoint 或重复事件。
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
     * 原子提交系统失败。仅当前 owner/token 且尚未进入业务终态的实例可以变为 FAILED；取得失败收口权后
     * 跳过待执行步骤、标记 Checkpoint 失败、写入脱敏 ERROR Outbox，并释放 conversation 锁。条件更新失败
     * 表示正常或其他失败收口已经提交，迟到异常被幂等忽略，不能覆盖 SUCCESS/PARTIAL_SUCCESS/REVIEW_BLOCKED。
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
     * 在收口事务提交后立即查询 OceanBase 事实表并按 sequence 投递本机连接；COMPLETE/ERROR 发送成功后
     * 事件服务关闭当前连接。查询或网络发送失败不会回滚已提交终态，连接游标保持不变，由定时 Poller 或
     * 客户端 Last-Event-ID 重连继续补偿。
     */
    public void flushEvents(String workflowInstanceId) {
        sseEventService.flushPersistedEvents(workflowInstanceId);
    }

    /**
     * 使用稳定 invocationId 构造主工作流调用流水，并通过 AgentMemoryService 在当前收口事务中同时更新
     * ChatMemory 窗口、追加 USER/ASSISTANT 长期记忆、upsert 会话主记录和写 SUCCESS 审计；未启用
     * local-db Memory 时直接跳过，避免默认 Profile 产生伪持久化。
     */
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
