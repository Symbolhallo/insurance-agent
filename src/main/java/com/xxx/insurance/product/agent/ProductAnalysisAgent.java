package com.xxx.insurance.product.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.xxx.insurance.ai.agent.AgentExecutionContext;
import com.xxx.insurance.ai.config.AiModelProperties;
import com.xxx.insurance.ai.memory.model.AgentMemoryExchange;
import com.xxx.insurance.ai.memory.model.AgentInvocationRecord;
import com.xxx.insurance.ai.memory.service.AgentMemoryService;
import com.xxx.insurance.common.exception.ErrorCode;
import com.xxx.insurance.common.util.TraceIdUtil;
import com.xxx.insurance.product.formatter.ProductAnalysisAnswerInspector;
import com.xxx.insurance.product.formatter.ProductAnalysisFormatter;
import com.xxx.insurance.product.model.ProductAnalysisAnswerInspection;
import com.xxx.insurance.product.model.ProductAnalysisChatRequest;
import com.xxx.insurance.product.model.ProductAnalysisChatResponse;
import com.xxx.insurance.product.model.ProductAnalysisRequest;
import com.xxx.insurance.product.model.ProductAnalysisResult;
import com.xxx.insurance.product.service.ProductAnalysisService;
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
 * 产品分析业务智能体入口。
 *
 * <p>当前类是 Phase1-Task3 的业务侧骨架，负责把“产品分析智能体”这个业务概念
 * 与 Spring AI Alibaba 的 {@link ReactAgent} 实例绑定起来。这样做有两个原因：</p>
 *
 * <ul>
 *     <li>业务代码只依赖 ProductAnalysisAgent，不直接散落使用 ReactAgent；</li>
 *     <li>后续接入 Tool、Memory、Formatter 时，可以保持业务入口稳定。</li>
 * </ul>
 *
 * <p>当前阶段同时提供两个边界：一个确定性分析入口用于测试 Mock Service 和 Formatter，
 * 一个受控模型调用入口用于验证 ReactAgent、Skill 和 Tool Calling 的单 Agent 闭环。</p>
 */
public class ProductAnalysisAgent {

    public static final String AGENT_NAME = "product-analysis-agent";

    public static final String AGENT_DESCRIPTION = "保险产品条款、保障责任、适用客群和风险提示的结构化分析智能体";

    private static final Logger log = LoggerFactory.getLogger(ProductAnalysisAgent.class);

    private final ReactAgent reactAgent;

    private final SkillsAgentHook skillsAgentHook;

    private final ProductAnalysisService productAnalysisService;

    private final ProductAnalysisFormatter productAnalysisFormatter;

    private final ProductAnalysisAnswerInspector productAnalysisAnswerInspector;

    private final AgentMemoryService agentMemoryService;

    private final AiModelProperties aiModelProperties;

    /** 创建产品分析业务入口并组合 ReactAgent、Skill、Tool、Formatter、Memory 和审计能力。 */
    public ProductAnalysisAgent(ReactAgent reactAgent,
                                SkillsAgentHook skillsAgentHook,
                                ProductAnalysisService productAnalysisService,
                                ProductAnalysisFormatter productAnalysisFormatter,
                                ProductAnalysisAnswerInspector productAnalysisAnswerInspector,
                                AgentMemoryService agentMemoryService,
                                AiModelProperties aiModelProperties) {
        this.reactAgent = reactAgent;
        this.skillsAgentHook = skillsAgentHook;
        this.productAnalysisService = productAnalysisService;
        this.productAnalysisFormatter = productAnalysisFormatter;
        this.productAnalysisAnswerInspector = productAnalysisAnswerInspector;
        this.agentMemoryService = agentMemoryService;
        this.aiModelProperties = aiModelProperties;
    }

    /** 返回 ReactAgent 注册名称。 */
    public String name() {
        return reactAgent.name();
    }

    /** 返回 ReactAgent 能力描述。 */
    public String description() {
        return reactAgent.description();
    }

    /**
     * 受控产品分析入口。
     *
     * <p>该方法当前只执行确定性 Mock 数据查询和格式化，不调用 {@link ReactAgent#call(String)}。
     * 这样可以先稳定产品域模型、Service接口和输出结构，后续再把它包装成
     * ProductAnalysisTool 交给 ReactAgent 调用。</p>
     */
    public ProductAnalysisResult analyze(ProductAnalysisRequest request) {
        validateRequest(request);
        return productAnalysisFormatter.format(productAnalysisService.queryProductAnalysisData(request));
    }

    /**
     * 受控模型调用入口。
     *
     * <p>这是 Phase1 单 Agent 闭环的模型调用边界。调用该方法会触发
     * {@link ReactAgent#call(String)}，ReactAgent 会根据 Skill 上下文决定是否读取
     * SKILL.md，并在需要产品数据时调用 product_analysis Tool。</p>
     *
     * <p>当应用启用 local-db profile 并创建 AgentMemoryService JDBC 实现时，该方法会使用
     * conversationId 读取历史消息，并通过 {@link ReactAgent#call(List)} 携带上下文调用模型。
     * 成功调用后，窗口记忆与长期记忆会在同一个事务内写入。默认 profile 下使用 no-op
     * 记忆服务，仍保持无记忆单轮调用。</p>
     */
    public ProductAnalysisChatResponse chat(ProductAnalysisChatRequest request) {
        String userMessage = request == null ? null : request.message();
        return chat(request, AgentExecutionContext.standalone(userMessage));
    }

    /**
     * Workflow 调用入口。
     *
     * <p>模型使用 request.message 中的标准化问题推理；记忆和调用审计使用
     * executionContext.originalUserMessage 保存用户原话，并关联 Workflow 实例与步骤。</p>
     */
    public ProductAnalysisChatResponse chat(ProductAnalysisChatRequest request,
                                            AgentExecutionContext executionContext) {
        validateChatRequest(request);
        Objects.requireNonNull(executionContext, "Agent execution context must not be null");
        String invocationId = newInvocationId();
        long startNanos = System.nanoTime();
        MemoryCallContext memoryCallContext = buildMemoryCallContext(request, executionContext);
        try {
            log.info("[Agent] name={} action=chat status=start invocationId={} conversationId={} messageLength={} memoryEnabled={} memoryMessageCount={}",
                    AGENT_NAME,
                    invocationId,
                    request.conversationId(),
                    request.message().length(),
                    memoryCallContext.memoryEnabled(),
                    memoryCallContext.historyMessageCount());
            AssistantMessage assistantMessage = callReactAgent(request, memoryCallContext);
            long durationMs = elapsedMillis(startNanos);
            String answer = assistantMessage.getText();
            Instant answeredAt = Instant.now();
            ProductAnalysisAnswerInspection inspection = productAnalysisAnswerInspector.inspect(answer);
            AgentInvocationRecord invocationRecord = successInvocationRecord(
                    request,
                    executionContext,
                    invocationId,
                    answer,
                    durationMs,
                    inspection,
                    answeredAt);
            saveMemory(memoryCallContext, invocationRecord, assistantMessage, answeredAt);
            log.info("[Agent] name={} action=chat status=success invocationId={} conversationId={} durationMs={} answerLength={} memoryEnabled={} outputFormatValid={}",
                    AGENT_NAME,
                    invocationId,
                    request.conversationId(),
                    durationMs,
                    answerLength(answer),
                    memoryCallContext.memoryEnabled(),
                    inspection.outputFormatValid());
            return new ProductAnalysisChatResponse(
                    AGENT_NAME,
                    request.conversationId(),
                    invocationId,
                    answer,
                    true,
                    durationMs,
                    answeredAt,
                    answerLength(answer),
                    memoryCallContext.memoryEnabled(),
                    memoryCallContext.historyMessageCount(),
                    inspection.outputFormatValid(),
                    inspection.missingSections());
        }
        catch (Exception ex) {
            long durationMs = elapsedMillis(startNanos);
            saveFailedInvocation(request, executionContext, invocationId, durationMs, ex);
            log.error("[Agent] name={} action=chat status=failed invocationId={} conversationId={} durationMs={}",
                    AGENT_NAME,
                    invocationId,
                    request.conversationId(),
                    durationMs,
                    ex);
            throw new IllegalStateException("Product analysis model invocation failed", ex);
        }
    }

    /**
     * 返回底层 Spring AI Alibaba ReactAgent。
     *
     * <p>该方法主要给后续 Workflow 或测试使用。普通业务层优先通过 ProductAnalysisAgent
     * 暴露的业务方法交互，避免未来替换 Agent 编排方式时扩大改造范围。</p>
     */
    public ReactAgent reactAgent() {
        return reactAgent;
    }

    /**
     * 返回当前智能体绑定的 Skill Hook。
     *
     * <p>保留该访问点是为了在后续阶段验证 Skill 与 Tool 的映射关系，
     * 当前不通过它执行 Tool Calling。</p>
     */
    public SkillsAgentHook skillsAgentHook() {
        return skillsAgentHook;
    }

    /** 校验确定性产品分析请求至少包含一个产品编码。 */
    private void validateRequest(ProductAnalysisRequest request) {
        Objects.requireNonNull(request, "Product analysis request must not be null");
        List<String> productCodes = request.productCodes();
        if (productCodes == null || productCodes.stream().allMatch(code -> code == null || code.isBlank())) {
            throw new IllegalArgumentException("At least one product code is required");
        }
    }

    /** 校验模型调用消息不能为空且不超过输入长度限制。 */
    private void validateChatRequest(ProductAnalysisChatRequest request) {
        Objects.requireNonNull(request, "Product analysis chat request must not be null");
        if (!StringUtils.hasText(request.message())) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (request.message().length() > 2000) {
            throw new IllegalArgumentException("message length must be less than or equal to 2000");
        }
    }

    /** 构建模型消息和持久化用户原话；DAG 子任务会显式禁用会话记忆。 */
    private MemoryCallContext buildMemoryCallContext(ProductAnalysisChatRequest request,
                                                     AgentExecutionContext executionContext) {
        if (!executionContext.conversationMemoryEnabled()
                || !agentMemoryService.isEnabled()
                || !StringUtils.hasText(request.conversationId())) {
            return MemoryCallContext.disabled();
        }
        List<Message> historyMessages = agentMemoryService.getHistory(request.conversationId());
        UserMessage modelUserMessage = new UserMessage(request.message());
        UserMessage persistedUserMessage = new UserMessage(executionContext.auditedUserMessage(request.message()));
        List<Message> requestMessages = new ArrayList<>(historyMessages);
        requestMessages.add(modelUserMessage);
        return new MemoryCallContext(
                true,
                request.conversationId(),
                persistedUserMessage,
                requestMessages,
                historyMessages.size());
    }

    /** 根据是否启用会话记忆选择单消息或历史消息列表调用 ReactAgent。 */
    private AssistantMessage callReactAgent(ProductAnalysisChatRequest request, MemoryCallContext memoryCallContext)
            throws Exception {
        if (!memoryCallContext.memoryEnabled()) {
            return reactAgent.call(request.message());
        }
        return reactAgent.call(memoryCallContext.requestMessages());
    }

    /** 保存完整对话记忆，或在 DAG 模式下只保存成功调用审计。 */
    private void saveMemory(MemoryCallContext memoryCallContext,
                            AgentInvocationRecord invocationRecord,
                            AssistantMessage assistantMessage,
                            Instant answeredAt) {
        if (!memoryCallContext.memoryEnabled()) {
            if (agentMemoryService.isEnabled() && StringUtils.hasText(invocationRecord.conversationId())) {
                agentMemoryService.saveSuccessfulInvocation(invocationRecord);
            }
            return;
        }
        agentMemoryService.saveSuccessfulExchange(new AgentMemoryExchange(
                memoryCallContext.conversationId(),
                invocationRecord.invocationId(),
                AGENT_NAME,
                memoryCallContext.userMessage(),
                assistantMessage,
                answeredAt), invocationRecord);
    }

    /** 尝试保存失败调用审计，持久化异常不会覆盖原始模型异常。 */
    private void saveFailedInvocation(ProductAnalysisChatRequest request,
                                      AgentExecutionContext executionContext,
                                      String invocationId,
                                      long durationMs,
                                      Exception exception) {
        if (!agentMemoryService.isEnabled() || !StringUtils.hasText(request.conversationId())) {
            return;
        }
        try {
            agentMemoryService.saveFailedInvocation(failedInvocationRecord(
                    request,
                    executionContext,
                    invocationId,
                    durationMs,
                    exception));
        }
        catch (Exception persistenceException) {
            log.warn("[Agent] name={} action=saveFailedInvocation status=failed invocationId={} conversationId={}",
                    AGENT_NAME,
                    invocationId,
                    request.conversationId(),
                    persistenceException);
        }
    }

    /** 构造包含回答格式检查结果的成功调用审计记录。 */
    private AgentInvocationRecord successInvocationRecord(ProductAnalysisChatRequest request,
                                                          AgentExecutionContext executionContext,
                                                          String invocationId,
                                                          String answer,
                                                          long durationMs,
                                                          ProductAnalysisAnswerInspection inspection,
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
                answerLength(answer),
                inspection.outputFormatValid(),
                inspection.missingSections(),
                "SUCCESS",
                null,
                null,
                createdAt);
    }

    /** 构造不包含助手回答的失败调用审计记录。 */
    private AgentInvocationRecord failedInvocationRecord(ProductAnalysisChatRequest request,
                                                         AgentExecutionContext executionContext,
                                                         String invocationId,
                                                         long durationMs,
                                                         Exception exception) {
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
                null,
                durationMs,
                null,
                null,
                List.of(),
                "FAILED",
                ErrorCode.AGENT_INVOKE_FAILED.code(),
                truncateErrorMessage(exception),
                Instant.now());
    }

    /** 从全局模型配置读取当前模型名称。 */
    private String modelName() {
        if (aiModelProperties.getChat() == null || aiModelProperties.getChat().getOptions() == null) {
            return null;
        }
        return aiModelProperties.getChat().getOptions().getModel();
    }

    /** 将异常消息截断到审计字段允许的长度。 */
    private String truncateErrorMessage(Exception exception) {
        if (exception == null || exception.getMessage() == null) {
            return null;
        }
        String message = exception.getMessage();
        if (message.length() <= 1024) {
            return message;
        }
        return message.substring(0, 1024);
    }

    /** 根据单调时钟计算 Agent 调用耗时。 */
    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 计算回答字符数量，空回答记为零。 */
    private int answerLength(String answer) {
        return answer == null ? 0 : answer.length();
    }

    /** 生成产品分析 Agent 调用编号。 */
    private String newInvocationId() {
        return "pai-" + UUID.randomUUID().toString().replace("-", "");
    }

    private record MemoryCallContext(
            boolean memoryEnabled,
            String conversationId,
            UserMessage userMessage,
            List<Message> requestMessages,
            int historyMessageCount) {

        /** 创建不携带历史消息的调用上下文。 */
        static MemoryCallContext disabled() {
            return new MemoryCallContext(false, null, null, List.of(), 0);
        }
    }
}
