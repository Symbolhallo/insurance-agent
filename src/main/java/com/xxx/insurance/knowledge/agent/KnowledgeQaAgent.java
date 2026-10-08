package com.xxx.insurance.knowledge.agent;

import io.agentscope.harness.agent.HarnessAgent;
import com.xxx.insurance.ai.agent.AgentExecutionContext;
import com.xxx.insurance.ai.agent.ConversationAgentExecutionResult;
import com.xxx.insurance.ai.agent.ConversationAwareAgentExecutor;
import com.xxx.insurance.ai.agent.ReactAgentStreamingExecutor;
import com.xxx.insurance.ai.config.AiModelProperties;
import com.xxx.insurance.ai.memory.service.AgentMemoryService;
import com.xxx.insurance.knowledge.model.KnowledgeQaChatRequest;
import com.xxx.insurance.knowledge.model.KnowledgeQaChatResponse;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * 保险业务知识问答智能体业务入口。
 *
 * <p>业务层通过该类调用 AgentScope HarnessAgent，并复用现有 OceanBase 会话窗口、长期记忆
 * 和调用审计事务边界。知识检索由 Agent 内部的 Tool Calling 完成。</p>
 */
public class KnowledgeQaAgent {

    public static final String AGENT_NAME = "knowledge-qa-agent";

    public static final String AGENT_DESCRIPTION = "保险合同、保险责任和保险主体等通用业务知识问答智能体";

    private final HarnessAgent reactAgent;

    private final ConversationAwareAgentExecutor conversationExecutor;

    /** 创建知识问答业务入口并组合 HarnessAgent、Skill、Memory 和审计配置。 */
    public KnowledgeQaAgent(HarnessAgent reactAgent,
                            AgentMemoryService agentMemoryService,
                            AiModelProperties aiModelProperties,
                            ReactAgentStreamingExecutor streamingExecutor) {
        this.reactAgent = reactAgent;
        this.conversationExecutor = new ConversationAwareAgentExecutor(
                agentMemoryService, aiModelProperties, streamingExecutor);
    }

    /** 创建复用统一会话执行器的知识问答业务入口。 */
    public KnowledgeQaAgent(HarnessAgent reactAgent,
                            ConversationAwareAgentExecutor conversationExecutor) {
        this.reactAgent = reactAgent;
        this.conversationExecutor = conversationExecutor;
    }

    /** 使用独立调用上下文执行知识问答，允许按 profile 读取和写入会话记忆。 */
    public KnowledgeQaChatResponse chat(KnowledgeQaChatRequest request) {
        String userMessage = request == null ? null : request.message();
        return chat(request, AgentExecutionContext.standalone(userMessage));
    }

    /**
     * 执行完整知识问答链路：按上下文选择历史记忆和事件流，由 HarnessAgent 渐进加载知识 Skill 并通过
     * Tool 获取事实；成功时独立模式原子保存问答记忆，DAG 模式只写调用流水，失败时尽力写 FAILED 审计。
     * Workflow/step/task 和用户原话均从 executionContext 进入审计，模型仍使用标准化子问题推理。
     */
    public KnowledgeQaChatResponse chat(KnowledgeQaChatRequest request,
                                        AgentExecutionContext executionContext) {
        validate(request);
        Objects.requireNonNull(executionContext, "Agent execution context must not be null");
        ConversationAgentExecutionResult result = conversationExecutor.execute(
                reactAgent,
                AGENT_NAME,
                "kqa-",
                request.message(),
                request.conversationId(),
                executionContext,
                null,
                "Knowledge QA model invocation failed");
        return new KnowledgeQaChatResponse(
                AGENT_NAME, request.conversationId(), result.invocationId(), result.answer(), true,
                result.durationMs(), result.answeredAt(), result.answerLength(), result.memoryEnabled(),
                result.historyMessageCount());
    }

    /** 返回 HarnessAgent 注册名称。 */
    public String name() {
        return reactAgent.getName();
    }

    /** 返回 HarnessAgent 能力描述。 */
    public String description() {
        return reactAgent.getDescription();
    }

    /** 返回底层 HarnessAgent，供装配验证和测试使用。 */
    public HarnessAgent reactAgent() {
        return reactAgent;
    }

    /** 校验知识问答请求不能为空且不超过模型输入长度限制。 */
    private void validate(KnowledgeQaChatRequest request) {
        Objects.requireNonNull(request, "Knowledge QA request must not be null");
        if (!StringUtils.hasText(request.message())) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (request.message().length() > 2000) {
            throw new IllegalArgumentException("message length must be less than or equal to 2000");
        }
    }

}
