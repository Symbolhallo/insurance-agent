package com.xxx.insurance.ai.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.xxx.insurance.ai.config.AiModelProperties;
import com.xxx.insurance.ai.memory.model.AgentInvocationRecord;
import com.xxx.insurance.ai.memory.service.AgentMemoryService;
import com.xxx.insurance.ai.workflow.model.SubAgentExecutionResult;
import com.xxx.insurance.common.exception.ErrorCode;
import com.xxx.insurance.common.util.TraceIdUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 为不直接维护对话记忆的领域 ReactAgent 提供统一模型调用、SSE 和调用审计。
 *
 * <p>保单与资产子智能体只保存调用流水；主工作流仍在 Summary 和审核完成后统一写入
 * ChatMemory 与长期记忆，避免并行任务竞争同一个 conversationId。</p>
 */
@Component
public class AuditedReactAgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AuditedReactAgentExecutor.class);

    private final AgentMemoryService agentMemoryService;
    private final AiModelProperties aiModelProperties;
    private final ReactAgentStreamingExecutor streamingExecutor;

    /** 创建统一执行器，组合持久化审计、当前模型标识和单次 ReactAgent 流式执行适配器。 */
    public AuditedReactAgentExecutor(AgentMemoryService agentMemoryService,
                                     AiModelProperties aiModelProperties,
                                     ReactAgentStreamingExecutor streamingExecutor) {
        this.agentMemoryService = agentMemoryService;
        this.aiModelProperties = aiModelProperties;
        this.streamingExecutor = streamingExecutor;
    }

    /**
     * 执行一次受审计的领域 Agent 调用。先校验查询并生成 invocationId，再按工作流上下文选择
     * ReactAgent.call 或单次 stream/ReAct Tool 循环，拒绝空最终答案；成功时记录耗时并只追加子任务调用
     * 流水（不并发覆盖会话 ChatMemory），返回统一 SubAgentExecutionResult。模型、Tool 或流异常时尽力写
     * FAILED 审计，审计持久化失败不会覆盖原始异常，最终统一抛出领域 Agent 调用失败。
     */
    public SubAgentExecutionResult execute(ReactAgent reactAgent,
                                           String agentName,
                                           String invocationPrefix,
                                           String query,
                                           String conversationId,
                                           AgentExecutionContext executionContext) {
        Objects.requireNonNull(reactAgent, "ReactAgent must not be null");
        Objects.requireNonNull(executionContext, "Agent execution context must not be null");
        validateQuery(query);
        String invocationId = invocationPrefix + UUID.randomUUID().toString().replace("-", "");
        long startedNanos = System.nanoTime();
        try {
            return executeAndRecordSuccess(
                    reactAgent, agentName, query, conversationId,
                    executionContext, invocationId, startedNanos);
        }
        catch (Exception ex) {
            long durationMs = elapsedMillis(startedNanos);
            saveFailure(agentName, invocationId, query, conversationId, executionContext, durationMs, ex);
            log.error("[Agent] name={} action=query status=failed invocationId={} durationMs={}",
                    agentName, invocationId, durationMs, ex);
            throw new IllegalStateException(agentName + " model invocation failed", ex);
        }
    }

    private void validateQuery(String query) {
        if (!StringUtils.hasText(query)) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (query.length() > 4000) {
            throw new IllegalArgumentException("query length must be less than or equal to 4000");
        }
    }

    /** 执行模型调用、校验答案并保存成功审计，返回统一子智能体结果。 */
    private SubAgentExecutionResult executeAndRecordSuccess(ReactAgent reactAgent,
                                                            String agentName,
                                                            String query,
                                                            String conversationId,
                                                            AgentExecutionContext executionContext,
                                                            String invocationId,
                                                            long startedNanos) throws Exception {
        log.info("[Agent] name={} action=query status=start invocationId={} conversationId={}",
                agentName, invocationId, conversationId);
        AssistantMessage assistantMessage = call(
                reactAgent, agentName, query, conversationId, executionContext);
        String answer = assistantMessage.getText();
        if (!StringUtils.hasText(answer)) {
            throw new IllegalStateException("ReactAgent returned blank answer");
        }

        long durationMs = elapsedMillis(startedNanos);
        Instant answeredAt = Instant.now();
        AgentInvocationRecord invocationRecord = invocation(
                agentName, invocationId, query, conversationId, executionContext,
                answer, durationMs, "SUCCESS", null, null, answeredAt);
        if (agentMemoryService.isEnabled() && StringUtils.hasText(conversationId)) {
            agentMemoryService.saveSuccessfulInvocation(invocationRecord);
        }
        log.info("[Agent] name={} action=query status=success invocationId={} durationMs={}",
                agentName, invocationId, durationMs);
        return new SubAgentExecutionResult(
                agentName, conversationId, invocationId, answer, true,
                durationMs, answeredAt, answer.length(), false, 0);
    }

    /**
     * 根据工作流 Token 开关选择同步 call 或单次 stream：流式路径用 workflow/task/agent/phase 构造独立
     * streamContext，逐块交给可靠 SSE Sink，同时从同一次 ReactAgent 最终 State 提取权威 AssistantMessage；
     * 独立非工作流调用没有 SSE 上下文，但仍复用相同最终消息校验。
     */
    private AssistantMessage call(ReactAgent reactAgent,
                                  String agentName,
                                  String query,
                                  String conversationId,
                                  AgentExecutionContext executionContext) throws Exception {
        if (!executionContext.tokenStreamingEnabled()) {
            return reactAgent.call(query);
        }
        AgentTokenStreamContext streamContext = StringUtils.hasText(executionContext.workflowInstanceId())
                ? new AgentTokenStreamContext(
                        executionContext.workflowInstanceId(),
                        conversationId,
                        executionContext.executionFenceToken(),
                        executionContext.taskId(),
                        agentName,
                        AgentTokenStreamContext.PHASE_SUB_AGENT)
                : null;
        return streamingExecutor.execute(reactAgent, query, streamContext);
    }

    /**
     * 在启用 local-db 且存在 conversationId 时构造 FAILED 调用流水并持久化；错误文本限制为数据库长度。
     * 该补偿自身异常只记录 WARN，确保调用方看到的 cause 始终是原始模型/Tool/流失败。
     */
    private void saveFailure(String agentName,
                             String invocationId,
                             String query,
                             String conversationId,
                             AgentExecutionContext executionContext,
                             long durationMs,
                             Exception exception) {
        if (!agentMemoryService.isEnabled() || !StringUtils.hasText(conversationId)) {
            return;
        }
        try {
            agentMemoryService.saveFailedInvocation(invocation(
                    agentName, invocationId, query, conversationId, executionContext,
                    null, durationMs, "FAILED", ErrorCode.AGENT_INVOKE_FAILED.code(),
                    truncate(exception), Instant.now()));
        }
        catch (Exception persistenceException) {
            log.warn("[Agent] name={} action=saveFailedInvocation status=failed invocationId={}",
                    agentName, invocationId, persistenceException);
        }
    }

    /** 创建金融链路统一调用流水。 */
    private AgentInvocationRecord invocation(String agentName,
                                             String invocationId,
                                             String query,
                                             String conversationId,
                                             AgentExecutionContext executionContext,
                                             String answer,
                                             long durationMs,
                                             String status,
                                             String errorCode,
                                             String errorMessage,
                                             Instant createdAt) {
        return new AgentInvocationRecord(
                invocationId,
                conversationId,
                agentName,
                TraceIdUtil.currentTraceId(),
                executionContext.workflowInstanceId(),
                executionContext.workflowStepId(),
                "openai-compatible",
                modelName(),
                "mock-user",
                "MOCK-CUSTOMER-001",
                "mock-operator",
                executionContext.auditedUserMessage(query),
                answer,
                durationMs,
                answer == null ? null : answer.length(),
                null,
                List.of(),
                status,
                errorCode,
                errorMessage,
                createdAt);
    }

    private String modelName() {
        if (aiModelProperties.getChat() == null || aiModelProperties.getChat().getOptions() == null) {
            return null;
        }
        return aiModelProperties.getChat().getOptions().getModel();
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private String truncate(Exception exception) {
        if (exception == null || exception.getMessage() == null) {
            return null;
        }
        return exception.getMessage().substring(0, Math.min(1024, exception.getMessage().length()));
    }
}
