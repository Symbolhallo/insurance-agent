package com.xxx.insurance.knowledge.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.xxx.insurance.ai.agent.AgentExecutionContext;
import com.xxx.insurance.ai.agent.ReactAgentStreamingExecutor;
import com.xxx.insurance.ai.config.AiModelProperties;
import com.xxx.insurance.ai.memory.model.AgentInvocationRecord;
import com.xxx.insurance.ai.memory.model.AgentMemoryExchange;
import com.xxx.insurance.ai.memory.service.AgentMemoryService;
import com.xxx.insurance.common.exception.ErrorCode;
import com.xxx.insurance.common.util.TraceIdUtil;
import com.xxx.insurance.knowledge.model.KnowledgeQaChatRequest;
import com.xxx.insurance.knowledge.model.KnowledgeQaChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 保险业务知识问答智能体业务入口。
 *
 * <p>业务层通过该类调用 Spring AI Alibaba ReactAgent，并复用现有 ChatMemory、长期记忆
 * 和调用审计事务边界。知识检索由 Agent 内部的 Tool Calling 完成。</p>
 */
public class KnowledgeQaAgent {

    public static final String AGENT_NAME = "knowledge-qa-agent";

    public static final String AGENT_DESCRIPTION = "保险合同、保险责任和保险主体等通用业务知识问答智能体";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeQaAgent.class);

    private final ReactAgent reactAgent;

    private final SkillsAgentHook skillsAgentHook;

    private final AgentMemoryService agentMemoryService;

    private final AiModelProperties aiModelProperties;

    private final ReactAgentStreamingExecutor streamingExecutor;

    /** 创建知识问答业务入口并组合 ReactAgent、Skill、Memory 和审计配置。 */
    public KnowledgeQaAgent(ReactAgent reactAgent,
                            SkillsAgentHook skillsAgentHook,
                            AgentMemoryService agentMemoryService,
                            AiModelProperties aiModelProperties,
                            ReactAgentStreamingExecutor streamingExecutor) {
        this.reactAgent = reactAgent;
        this.skillsAgentHook = skillsAgentHook;
        this.agentMemoryService = agentMemoryService;
        this.aiModelProperties = aiModelProperties;
        this.streamingExecutor = streamingExecutor;
    }

    /** 使用独立调用上下文执行知识问答，允许按 profile 读取和写入会话记忆。 */
    public KnowledgeQaChatResponse chat(KnowledgeQaChatRequest request) {
        String userMessage = request == null ? null : request.message();
        return chat(request, AgentExecutionContext.standalone(userMessage));
    }

    /** 使用 Workflow 提供的链路上下文执行知识问答并关联调用审计。 */
    public KnowledgeQaChatResponse chat(KnowledgeQaChatRequest request,
                                        AgentExecutionContext executionContext) {
        validate(request);
        Objects.requireNonNull(executionContext, "Agent execution context must not be null");
        String invocationId = "kqa-" + UUID.randomUUID().toString().replace("-", "");
        long startNanos = System.nanoTime();
        MemoryCallContext memoryContext = buildMemoryContext(request, executionContext);
        try {
            log.info("[Agent] name={} action=chat status=start invocationId={} conversationId={} memoryEnabled={} memoryMessageCount={}",
                    AGENT_NAME, invocationId, request.conversationId(), memoryContext.enabled(),
                    memoryContext.historyMessageCount());
            AssistantMessage assistantMessage = callReactAgent(request, memoryContext, executionContext);
            String answer = assistantMessage.getText();
            long durationMs = elapsedMillis(startNanos);
            Instant answeredAt = Instant.now();
            AgentInvocationRecord invocationRecord = invocationRecord(
                    request, executionContext, invocationId, answer, durationMs, "SUCCESS", null, null, answeredAt);
            if (memoryContext.enabled()) {
                agentMemoryService.saveSuccessfulExchange(new AgentMemoryExchange(
                        memoryContext.conversationId(),
                        invocationId,
                        AGENT_NAME,
                        memoryContext.persistedUserMessage(),
                        assistantMessage,
                        answeredAt), invocationRecord);
            }
            else if (agentMemoryService.isEnabled() && StringUtils.hasText(request.conversationId())) {
                agentMemoryService.saveSuccessfulInvocation(invocationRecord);
            }
            log.info("[Agent] name={} action=chat status=success invocationId={} conversationId={} durationMs={}",
                    AGENT_NAME, invocationId, request.conversationId(), durationMs);
            return new KnowledgeQaChatResponse(
                    AGENT_NAME,
                    request.conversationId(),
                    invocationId,
                    answer,
                    true,
                    durationMs,
                    answeredAt,
                    answerLength(answer),
                    memoryContext.enabled(),
                    memoryContext.historyMessageCount());
        }
        catch (Exception ex) {
            long durationMs = elapsedMillis(startNanos);
            saveFailedInvocation(request, executionContext, invocationId, durationMs, ex);
            log.error("[Agent] name={} action=chat status=failed invocationId={} conversationId={} durationMs={}",
                    AGENT_NAME, invocationId, request.conversationId(), durationMs, ex);
            throw new IllegalStateException("Knowledge QA model invocation failed", ex);
        }
    }

    /** 返回 ReactAgent 注册名称。 */
    public String name() {
        return reactAgent.name();
    }

    /** 返回 ReactAgent 能力描述。 */
    public String description() {
        return reactAgent.description();
    }

    /** 返回底层 ReactAgent，供装配验证和测试使用。 */
    public ReactAgent reactAgent() {
        return reactAgent;
    }

    /** 返回知识问答专属 Skill Hook，供 Skill 隔离验证使用。 */
    public SkillsAgentHook skillsAgentHook() {
        return skillsAgentHook;
    }

    /** 根据是否启用会话记忆选择单消息或历史消息列表调用 ReactAgent。 */
    private AssistantMessage callReactAgent(KnowledgeQaChatRequest request,
                                            MemoryCallContext memoryContext,
                                            AgentExecutionContext executionContext) throws Exception {
        if (executionContext.tokenStreamingEnabled()) {
            if (!memoryContext.enabled()) {
                return streamingExecutor.execute(reactAgent, request.message());
            }
            return streamingExecutor.execute(reactAgent, memoryContext.requestMessages());
        }
        if (!memoryContext.enabled()) {
            return reactAgent.call(request.message());
        }
        return reactAgent.call(memoryContext.requestMessages());
    }

    /** 构建模型消息和持久化用户原话；DAG 子任务会显式禁用会话记忆。 */
    private MemoryCallContext buildMemoryContext(KnowledgeQaChatRequest request,
                                                 AgentExecutionContext executionContext) {
        if (!executionContext.conversationMemoryEnabled()
                || !agentMemoryService.isEnabled()
                || !StringUtils.hasText(request.conversationId())) {
            return MemoryCallContext.disabled();
        }
        List<Message> history = agentMemoryService.getHistory(request.conversationId());
        List<Message> requestMessages = new ArrayList<>(history);
        requestMessages.add(new UserMessage(request.message()));
        return new MemoryCallContext(
                true,
                request.conversationId(),
                new UserMessage(executionContext.auditedUserMessage(request.message())),
                requestMessages,
                history.size());
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

    /** 尝试保存失败调用审计，审计持久化异常不会覆盖原始模型异常。 */
    private void saveFailedInvocation(KnowledgeQaChatRequest request,
                                      AgentExecutionContext executionContext,
                                      String invocationId,
                                      long durationMs,
                                      Exception exception) {
        if (!agentMemoryService.isEnabled() || !StringUtils.hasText(request.conversationId())) {
            return;
        }
        try {
            agentMemoryService.saveFailedInvocation(invocationRecord(
                    request,
                    executionContext,
                    invocationId,
                    null,
                    durationMs,
                    "FAILED",
                    ErrorCode.AGENT_INVOKE_FAILED.code(),
                    truncate(exception),
                    Instant.now()));
        }
        catch (Exception persistenceException) {
            log.warn("[Agent] name={} action=saveFailedInvocation status=failed invocationId={}",
                    AGENT_NAME, invocationId, persistenceException);
        }
    }

    /** 构造成功或失败的统一 Agent 调用审计记录。 */
    private AgentInvocationRecord invocationRecord(KnowledgeQaChatRequest request,
                                                   AgentExecutionContext executionContext,
                                                   String invocationId,
                                                   String answer,
                                                   long durationMs,
                                                   String status,
                                                   String errorCode,
                                                   String errorMessage,
                                                   Instant createdAt) {
        return new AgentInvocationRecord(
                invocationId,
                request.conversationId(),
                AGENT_NAME,
                TraceIdUtil.currentTraceId(),
                executionContext.workflowInstanceId(),
                executionContext.workflowStepId(),
                "openai-compatible",
                modelName(),
                "mock-user",
                "mock-customer",
                "mock-operator",
                executionContext.auditedUserMessage(request.message()),
                answer,
                durationMs,
                answer == null ? null : answerLength(answer),
                null,
                List.of(),
                status,
                errorCode,
                errorMessage,
                createdAt);
    }

    /** 从全局模型配置读取当前模型名称。 */
    private String modelName() {
        if (aiModelProperties.getChat() == null || aiModelProperties.getChat().getOptions() == null) {
            return null;
        }
        return aiModelProperties.getChat().getOptions().getModel();
    }

    /** 将异常消息截断到审计字段允许的长度。 */
    private String truncate(Exception exception) {
        if (exception == null || exception.getMessage() == null) {
            return null;
        }
        return exception.getMessage().substring(0, Math.min(1024, exception.getMessage().length()));
    }

    /** 根据单调时钟计算 Agent 调用耗时。 */
    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /** 计算回答字符数量，空回答记为零。 */
    private int answerLength(String answer) {
        return answer == null ? 0 : answer.length();
    }

    private record MemoryCallContext(
            boolean enabled,
            String conversationId,
            UserMessage persistedUserMessage,
            List<Message> requestMessages,
            int historyMessageCount) {

        /** 创建不携带历史消息的调用上下文。 */
        static MemoryCallContext disabled() {
            return new MemoryCallContext(false, null, null, List.of(), 0);
        }
    }
}
