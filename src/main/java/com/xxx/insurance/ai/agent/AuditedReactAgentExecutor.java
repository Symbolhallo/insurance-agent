package com.xxx.insurance.ai.agent;

import com.xxx.insurance.ai.config.AiModelProperties;
import com.xxx.insurance.ai.memory.service.AgentMemoryService;
import com.xxx.insurance.ai.workflow.model.SubAgentExecutionResult;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * 为不直接维护对话记忆的领域 HarnessAgent 提供统一模型调用、SSE 和调用审计。
 *
 * <p>保单与资产子智能体只保存调用流水；主工作流仍在 Summary 和审核完成后统一写入
 * 会话窗口与长期记忆，避免并行任务竞争同一个 conversationId。公共执行生命周期委托给
 * {@link ConversationAwareAgentExecutor}，本类只保留无记忆策略、输入限制和结果适配。</p>
 */
@Component
public class AuditedReactAgentExecutor {

    private final ConversationAwareAgentExecutor conversationExecutor;

    /** 生产装配直接复用统一会话执行器 Bean。 */
    @Autowired
    public AuditedReactAgentExecutor(ConversationAwareAgentExecutor conversationExecutor) {
        this.conversationExecutor = conversationExecutor;
    }

    /** 保留原有依赖构造边界，供独立装配和既有测试使用。 */
    public AuditedReactAgentExecutor(AgentMemoryService agentMemoryService,
                                     AiModelProperties aiModelProperties,
                                     ReactAgentStreamingExecutor streamingExecutor) {
        this.conversationExecutor = new ConversationAwareAgentExecutor(
                agentMemoryService, aiModelProperties, streamingExecutor);
    }

    /**
     * 执行一次受审计的领域 Agent 调用。保单/资产调用无论外部上下文如何都禁用对话窗口读写，
     * 只保存 invocation；Token 流、身份、workflow/step/task 和 fencing token 原样保留。
     */
    public SubAgentExecutionResult execute(HarnessAgent reactAgent,
                                           String agentName,
                                           String invocationPrefix,
                                           String query,
                                           String conversationId,
                                           AgentExecutionContext executionContext) {
        Objects.requireNonNull(executionContext, "Agent execution context must not be null");
        validateQuery(query);
        AgentExecutionContext invocationOnlyContext = new AgentExecutionContext(
                executionContext.workflowInstanceId(),
                executionContext.workflowStepId(),
                executionContext.executionFenceToken(),
                executionContext.originalUserMessage(),
                false,
                executionContext.taskId(),
                executionContext.tokenStreamingEnabled(),
                executionContext.identity());
        ConversationAgentExecutionResult result = conversationExecutor.execute(
                reactAgent,
                agentName,
                invocationPrefix,
                query,
                conversationId,
                invocationOnlyContext,
                null,
                agentName + " model invocation failed");
        return new SubAgentExecutionResult(
                agentName,
                conversationId,
                result.invocationId(),
                result.answer(),
                true,
                result.durationMs(),
                result.answeredAt(),
                result.answerLength(),
                false,
                0);
    }

    private void validateQuery(String query) {
        if (!StringUtils.hasText(query)) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (query.length() > 4000) {
            throw new IllegalArgumentException("query length must be less than or equal to 4000");
        }
    }
}
