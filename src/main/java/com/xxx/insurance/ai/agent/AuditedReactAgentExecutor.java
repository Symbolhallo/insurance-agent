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

    /** 创建统一执行器并注入审计、模型配置和流式执行能力。 */
    public AuditedReactAgentExecutor(AgentMemoryService agentMemoryService,
                                     AiModelProperties aiModelProperties,
                                     ReactAgentStreamingExecutor streamingExecutor) {
        this.agentMemoryService = agentMemoryService;
        this.aiModelProperties = aiModelProperties;
        this.streamingExecutor = streamingExecutor;
    }

    /** 调用真实 ReactAgent，并将成功或失败结果写入 Agent 调用流水。 */
    public SubAgentExecutionResult execute(ReactAgent reactAgent,
                                           String agentName,
                                           String invocationPrefix,
                                           String query,
                                           String conversationId,
                                           AgentExecutionContext executionContext) {
        Objects.requireNonNull(reactAgent, "ReactAgent must not be null");
        Objects.requireNonNull(executionContext, "Agent execution context must not be null");
        if (!StringUtils.hasText(query)) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (query.length() > 4000) {
            throw new IllegalArgumentException("query length must be less than or equal to 4000");
        }
        String invocationId = invocationPrefix + UUID.randomUUID().toString().replace("-", "");
        long startedNanos = System.nanoTime();
        try {
            log.info("[Agent] name={} action=query status=start invocationId={} conversationId={}",
                    agentName, invocationId, conversationId);
            AssistantMessage message = call(
                    reactAgent, agentName, query, conversationId, executionContext);
            if (!StringUtils.hasText(message.getText())) {
                throw new IllegalStateException("ReactAgent returned blank answer");
            }
            long durationMs = elapsedMillis(startedNanos);
            Instant answeredAt = Instant.now();
            AgentInvocationRecord invocation = invocation(
                    agentName, invocationId, query, conversationId, executionContext,
                    message.getText(), durationMs, "SUCCESS", null, null, answeredAt);
            if (agentMemoryService.isEnabled() && StringUtils.hasText(conversationId)) {
                agentMemoryService.saveSuccessfulInvocation(invocation);
            }
            log.info("[Agent] name={} action=query status=success invocationId={} durationMs={}",
                    agentName, invocationId, durationMs);
            return new SubAgentExecutionResult(
                    agentName,
                    conversationId,
                    invocationId,
                    message.getText(),
                    true,
                    durationMs,
                    answeredAt,
                    message.getText().length(),
                    false,
                    0);
        }
        catch (Exception ex) {
            long durationMs = elapsedMillis(startedNanos);
            saveFailure(agentName, invocationId, query, conversationId, executionContext, durationMs, ex);
            log.error("[Agent] name={} action=query status=failed invocationId={} durationMs={}",
                    agentName, invocationId, durationMs, ex);
            throw new IllegalStateException(agentName + " model invocation failed", ex);
        }
    }

    /** 根据工作流 SSE 开关选择 call 或 stream，流内容使用当前任务标识发布。 */
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

    /** 审计失败，但不让审计异常覆盖原始模型异常。 */
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
