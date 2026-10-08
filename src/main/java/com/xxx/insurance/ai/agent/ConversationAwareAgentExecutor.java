package com.xxx.insurance.ai.agent;

import com.xxx.insurance.ai.config.AiModelProperties;
import com.xxx.insurance.ai.memory.model.AgentInvocationRecord;
import com.xxx.insurance.ai.memory.model.AgentMemoryExchange;
import com.xxx.insurance.ai.memory.service.AgentMemoryService;
import com.xxx.insurance.common.exception.ErrorCode;
import com.xxx.insurance.common.util.TraceIdUtil;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * 统一执行需要会话记忆的领域 Agent，收敛历史装载、流式调用、调用审计和失败补偿。
 *
 * <p>独立调用会原子保存用户/助手消息和成功流水；DAG 子任务禁用会话消息写入，仅追加调用流水，
 * 避免并行节点竞争同一 conversation 窗口。领域 Agent 仍负责请求校验、输出合同检查和响应模型映射。</p>
 */
@Component
public class ConversationAwareAgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(ConversationAwareAgentExecutor.class);

    private final AgentMemoryService agentMemoryService;
    private final AiModelProperties aiModelProperties;
    private final ReactAgentStreamingExecutor streamingExecutor;

    public ConversationAwareAgentExecutor(AgentMemoryService agentMemoryService,
                                          AiModelProperties aiModelProperties,
                                          ReactAgentStreamingExecutor streamingExecutor) {
        this.agentMemoryService = agentMemoryService;
        this.aiModelProperties = aiModelProperties;
        this.streamingExecutor = streamingExecutor;
    }

    /** 执行一次带可选领域输出检查的 Agent 调用。 */
    public ConversationAgentExecutionResult execute(HarnessAgent reactAgent,
                                                    String agentName,
                                                    String invocationPrefix,
                                                    String message,
                                                    String conversationId,
                                                    AgentExecutionContext executionContext,
                                                    Function<String, ConversationAgentOutputInspection> outputInspector,
                                                    String failureMessage) {
        Objects.requireNonNull(reactAgent, "HarnessAgent must not be null");
        Objects.requireNonNull(executionContext, "Agent execution context must not be null");
        String invocationId = invocationPrefix + UUID.randomUUID().toString().replace("-", "");
        long startedNanos = System.nanoTime();
        MemoryCallContext memory = buildMemoryContext(message, conversationId, executionContext);
        try {
            log.info("[Agent] name={} action=chat status=start invocationId={} conversationId={} messageLength={} memoryEnabled={} memoryMessageCount={}",
                    agentName, invocationId, conversationId, message.length(), memory.enabled(), memory.historyCount());
            AssistantMessage assistantMessage = call(
                    reactAgent, agentName, message, conversationId, executionContext, memory);
            String answer = assistantMessage.getTextContent();
            long durationMs = elapsedMillis(startedNanos);
            Instant answeredAt = Instant.now();
            ConversationAgentOutputInspection inspection = outputInspector == null
                    ? ConversationAgentOutputInspection.notInspected()
                    : Objects.requireNonNull(outputInspector.apply(answer), "Output inspection must not be null");
            AgentInvocationRecord invocation = invocation(
                    agentName, invocationId, message, conversationId, executionContext, answer, durationMs,
                    inspection, "SUCCESS", null, null, answeredAt);
            saveSuccess(memory, invocation, assistantMessage, answeredAt, executionContext, agentName);
            log.info("[Agent] name={} action=chat status=success invocationId={} conversationId={} durationMs={} answerLength={} memoryEnabled={} outputFormatValid={}",
                    agentName, invocationId, conversationId, durationMs, answerLength(answer), memory.enabled(), inspection.valid());
            return new ConversationAgentExecutionResult(
                    invocationId, answer, durationMs, answeredAt, answerLength(answer),
                    memory.enabled(), memory.historyCount(), inspection.valid(), inspection.missingSections());
        }
        catch (Exception ex) {
            long durationMs = elapsedMillis(startedNanos);
            saveFailure(agentName, invocationId, message, conversationId, executionContext, durationMs, ex);
            log.error("[Agent] name={} action=chat status=failed invocationId={} conversationId={} durationMs={}",
                    agentName, invocationId, conversationId, durationMs, ex);
            throw new IllegalStateException(failureMessage, ex);
        }
    }

    private MemoryCallContext buildMemoryContext(String message,
                                                 String conversationId,
                                                 AgentExecutionContext executionContext) {
        if (!executionContext.conversationMemoryEnabled()
                || !agentMemoryService.isEnabled()
                || !StringUtils.hasText(conversationId)) {
            return MemoryCallContext.disabled();
        }
        List<Msg> history = agentMemoryService.getHistory(conversationId);
        List<Msg> requestMessages = new ArrayList<>(history);
        requestMessages.add(new UserMessage(message));
        return new MemoryCallContext(
                true,
                conversationId,
                new UserMessage(executionContext.auditedUserMessage(message)),
                requestMessages,
                history.size());
    }

    private AssistantMessage call(HarnessAgent reactAgent,
                                  String agentName,
                                  String message,
                                  String conversationId,
                                  AgentExecutionContext executionContext,
                                  MemoryCallContext memory) throws Exception {
        AgentTokenStreamContext streamContext = executionContext.tokenStreamingEnabled()
                && StringUtils.hasText(executionContext.workflowInstanceId())
                ? new AgentTokenStreamContext(
                        executionContext.workflowInstanceId(), conversationId,
                        executionContext.executionFenceToken(), executionContext.taskId(),
                        agentName, AgentTokenStreamContext.PHASE_SUB_AGENT)
                : null;
        if (!memory.enabled()) {
            return streamingExecutor.execute(reactAgent, message, streamContext);
        }
        return streamingExecutor.execute(reactAgent, memory.requestMessages(), streamContext);
    }

    private void saveSuccess(MemoryCallContext memory,
                             AgentInvocationRecord invocation,
                             AssistantMessage assistantMessage,
                             Instant answeredAt,
                             AgentExecutionContext executionContext,
                             String agentName) {
        if (!memory.enabled()) {
            if (agentMemoryService.isEnabled() && StringUtils.hasText(invocation.conversationId())) {
                agentMemoryService.saveSuccessfulInvocation(invocation);
            }
            return;
        }
        agentMemoryService.saveSuccessfulExchange(new AgentMemoryExchange(
                memory.conversationId(), invocation.invocationId(), agentName,
                memory.persistedUserMessage(), assistantMessage, answeredAt, executionContext.identity()), invocation);
    }

    private void saveFailure(String agentName,
                             String invocationId,
                             String message,
                             String conversationId,
                             AgentExecutionContext executionContext,
                             long durationMs,
                             Exception exception) {
        if (!agentMemoryService.isEnabled() || !StringUtils.hasText(conversationId)) {
            return;
        }
        try {
            agentMemoryService.saveFailedInvocation(invocation(
                    agentName, invocationId, message, conversationId, executionContext, null, durationMs,
                    ConversationAgentOutputInspection.notInspected(), "FAILED",
                    ErrorCode.AGENT_INVOKE_FAILED.code(), truncate(exception), Instant.now()));
        }
        catch (Exception persistenceException) {
            log.warn("[Agent] name={} action=saveFailedInvocation status=failed invocationId={}",
                    agentName, invocationId, persistenceException);
        }
    }

    private AgentInvocationRecord invocation(String agentName,
                                             String invocationId,
                                             String message,
                                             String conversationId,
                                             AgentExecutionContext executionContext,
                                             String answer,
                                             long durationMs,
                                             ConversationAgentOutputInspection inspection,
                                             String status,
                                             String errorCode,
                                             String errorMessage,
                                             Instant createdAt) {
        return new AgentInvocationRecord(
                invocationId, conversationId, executionContext.identity().tenantId(), agentName,
                TraceIdUtil.currentTraceId(), executionContext.workflowInstanceId(),
                executionContext.workflowStepId(), "openai-compatible", aiModelProperties.getModelName(),
                executionContext.identity().userId(), executionContext.identity().customerId(),
                executionContext.identity().operatorId(), executionContext.auditedUserMessage(message), answer,
                durationMs, answer == null ? null : answerLength(answer), inspection.valid(),
                inspection.missingSections(), status, errorCode, errorMessage, createdAt);
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private int answerLength(String answer) {
        return answer == null ? 0 : answer.length();
    }

    private String truncate(Exception exception) {
        if (exception == null || exception.getMessage() == null) {
            return null;
        }
        String message = exception.getMessage();
        return message.substring(0, Math.min(1024, message.length()));
    }

    private record MemoryCallContext(
            boolean enabled,
            String conversationId,
            UserMessage persistedUserMessage,
            List<Msg> requestMessages,
            int historyCount) {

        private static MemoryCallContext disabled() {
            return new MemoryCallContext(false, null, null, List.of(), 0);
        }
    }
}
