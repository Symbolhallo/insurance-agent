package com.xxx.insurance.ai.workflow.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.workflow.checkpoint.OceanBaseCheckpointSaver;
import com.xxx.insurance.common.exception.BusinessException;
import com.xxx.insurance.common.exception.ErrorCode;
import com.xxx.insurance.ai.workflow.config.WorkflowExecutionConfig;
import com.xxx.insurance.ai.config.AiModelProperties;
import com.xxx.insurance.ai.memory.model.AgentInvocationRecord;
import com.xxx.insurance.ai.memory.model.AgentMemoryExchange;
import com.xxx.insurance.ai.memory.service.AgentMemoryService;
import com.xxx.insurance.ai.workflow.config.MainWorkflowGraphConfig;
import com.xxx.insurance.ai.workflow.mapper.WorkflowExecutionMapper;
import com.xxx.insurance.ai.workflow.model.AlignedWorkflowContext;
import com.xxx.insurance.ai.workflow.model.AgentTaskStatus;
import com.xxx.insurance.ai.workflow.model.DagExecutionResult;
import com.xxx.insurance.ai.workflow.model.IntentRoutingResult;
import com.xxx.insurance.ai.workflow.model.MainWorkflowRequest;
import com.xxx.insurance.ai.workflow.model.MainWorkflowResponse;
import com.xxx.insurance.ai.workflow.model.MainWorkflowStateKeys;
import com.xxx.insurance.ai.workflow.model.ProductRecallDecision;
import com.xxx.insurance.ai.workflow.model.ProductReferenceResolution;
import com.xxx.insurance.ai.workflow.model.OutputReviewDecision;
import com.xxx.insurance.ai.workflow.model.OutputReviewResult;
import com.xxx.insurance.ai.workflow.model.SubAgentExecutionResult;
import com.xxx.insurance.ai.workflow.model.WorkflowInstanceExecutionView;
import com.xxx.insurance.ai.workflow.model.WorkflowInstanceRecord;
import com.xxx.insurance.ai.workflow.model.WorkflowNodeDefinition;
import com.xxx.insurance.ai.workflow.model.WorkflowPlan;
import com.xxx.insurance.ai.workflow.model.WorkflowStepRecord;
import com.xxx.insurance.ai.workflow.model.WorkflowSummaryResult;
import com.xxx.insurance.ai.workflow.model.WorkflowSseEventType;
import com.xxx.insurance.ai.workflow.model.WorkflowResumeRequest;
import com.xxx.insurance.common.util.TraceIdUtil;
import com.xxx.insurance.product.model.ConfirmedProduct;
import com.xxx.insurance.product.model.ProductCandidate;
import com.xxx.insurance.product.model.ProductConfirmationRequest;
import com.xxx.insurance.product.model.ProductRecallResult;
import com.xxx.insurance.product.service.ConversationConfirmedProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 本地数据库版主工作流服务。
 *
 * <p>首次运行可能在产品人工确认节点前结束当前 HTTP 请求。确认接口从 OceanBase
 * Checkpoint 恢复 Graph，不持有原请求线程。业务 Agent 仍只负责领域能力，暂停、恢复和
 * 执行状态持久化由本服务协调。</p>
 */
@Service
@Profile("local-db")
public class LocalDbMainWorkflowService implements MainWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(LocalDbMainWorkflowService.class);

    private static final String STATUS_RUNNING = "RUNNING";

    private static final String STATUS_WAITING_CONFIRM = "WAITING_CONFIRM";

    private static final String STATUS_CONFIRMING = "CONFIRMING";

    private final WorkflowExecutionMapper workflowExecutionMapper;

    private final CompiledGraph mainWorkflowGraph;

    private final ObjectMapper objectMapper;

    private final OceanBaseCheckpointSaver checkpointSaver;

    private final ConversationConfirmedProductService confirmedProductService;

    private final AgentMemoryService agentMemoryService;

    private final AiModelProperties aiModelProperties;

    private final WorkflowEventPublisher workflowEventPublisher;

    private final ThreadPoolTaskExecutor workflowDagTaskExecutor;

    public LocalDbMainWorkflowService(WorkflowExecutionMapper workflowExecutionMapper,
                                      @Qualifier(MainWorkflowGraphConfig.MAIN_WORKFLOW_GRAPH)
                                      CompiledGraph mainWorkflowGraph,
                                      ObjectMapper objectMapper,
                                      OceanBaseCheckpointSaver checkpointSaver,
                                      ConversationConfirmedProductService confirmedProductService,
                                      AgentMemoryService agentMemoryService,
                                      AiModelProperties aiModelProperties,
                                      WorkflowEventPublisher workflowEventPublisher,
                                      @Qualifier(WorkflowExecutionConfig.WORKFLOW_DAG_TASK_EXECUTOR)
                                      ThreadPoolTaskExecutor workflowDagTaskExecutor) {
        this.workflowExecutionMapper = workflowExecutionMapper;
        this.mainWorkflowGraph = mainWorkflowGraph;
        this.objectMapper = objectMapper;
        this.checkpointSaver = checkpointSaver;
        this.confirmedProductService = confirmedProductService;
        this.agentMemoryService = agentMemoryService;
        this.aiModelProperties = aiModelProperties;
        this.workflowEventPublisher = workflowEventPublisher;
        this.workflowDagTaskExecutor = workflowDagTaskExecutor;
    }

    /** 生成符合数据库字段长度和日志规范的工作流实例编号。 */
    @Override
    public String createWorkflowInstanceId() {
        return newWorkflowInstanceId();
    }

    /**
     * 创建工作流实例并从 START 执行 Main Graph。
     *
     * <p>Graph 若在 human-confirm-product 前中断，本方法返回 WAITING_CONFIRM；否则读取 END
     * 状态并统一完成审计、记忆与 Checkpoint 收口。HTTP 请求不会跨人工确认阶段长期占用线程。</p>
     */
    @Override
    public MainWorkflowResponse run(MainWorkflowRequest request) {
        return run(createWorkflowInstanceId(), request);
    }

    /**
     * 使用预先分配的实例编号创建数据库记录并执行 Main Graph。
     *
     * <p>SSE 入口会先用该编号注册连接，再在独立线程调用本方法，确保首个 start 事件不丢失。</p>
     */
    @Override
    public MainWorkflowResponse run(String workflowInstanceId, MainWorkflowRequest request) {
        return run(workflowInstanceId, request, false);
    }

    /**
     * 使用预先分配的实例编号执行 Main Graph，并把 Token 流开关写入可恢复 Graph State。
     */
    @Override
    public MainWorkflowResponse run(String workflowInstanceId,
                                    MainWorkflowRequest request,
                                    boolean tokenStreamingEnabled) {
        Instant startedAt = Instant.now();
        String inputJson = toJson(request);
        Map<String, String> workflowStepIds = createWorkflowSteps();

        log.info("[Workflow] code={} action=run status=start workflowInstanceId={} conversationId={}",
                WORKFLOW_CODE, workflowInstanceId, request.conversationId());
        // 主工作流链路 2：先持久化执行实例和全部可能步骤，为暂停、恢复和分支跳过保留审计位置。
        workflowExecutionMapper.insertInstance(new WorkflowInstanceRecord(
                workflowInstanceId,
                WORKFLOW_CODE,
                request.conversationId(),
                TraceIdUtil.currentTraceId(),
                STATUS_RUNNING,
                inputJson,
                startedAt));
        insertWorkflowSteps(workflowInstanceId, workflowStepIds, inputJson, startedAt);
        publishEvent(workflowInstanceId, request.conversationId(), WorkflowSseEventType.START, null,
                Map.of("status", STATUS_RUNNING, "workflowCode", WORKFLOW_CODE));

        try {
            // 主工作流链路 3：以工作流实例 ID 作为 threadId 启动 Graph，候选召回后可持久化中断。
            RunnableConfig config = runnableConfig(workflowInstanceId, request.conversationId());
            NodeOutput output = mainWorkflowGraph.invokeAndGetOutput(
                            Map.of(
                                    MainWorkflowStateKeys.REQUEST, request,
                                    MainWorkflowStateKeys.WORKFLOW_INSTANCE_ID, workflowInstanceId,
                                    MainWorkflowStateKeys.WORKFLOW_STEP_IDS, workflowStepIds,
                                    MainWorkflowStateKeys.TOKEN_STREAMING_ENABLED, tokenStreamingEnabled),
                            config)
                    .orElseThrow(() -> new IllegalStateException("Main workflow graph returned empty output"));
            if (!output.isEND()) {
                StateSnapshot snapshot = mainWorkflowGraph.getState(config);
                return waitingConfirmResponse(snapshot, request, workflowInstanceId, startedAt);
            }
            return complete(output.state(), workflowInstanceId, startedAt);
        }
        catch (Exception ex) {
            fail(workflowInstanceId, ex);
            throw new IllegalStateException("Main workflow execution failed", ex);
        }
    }

    /**
     * 校验并持久化当前候选选择，再从 OceanBase Checkpoint 恢复原 Graph。
     *
     * <p>{@code updateState} 写入标准产品实体，{@code withResume} 使 Graph 从中断点继续进入
     * human-confirm-product，随后按既定边流向 context-alignment。</p>
     */
    @Override
    public MainWorkflowResponse confirmProducts(String workflowInstanceId,
                                                ProductConfirmationRequest request) {
        return confirmProducts(workflowInstanceId, request, false);
    }

    /** 确认产品并按调用入口指定的流式模式原子抢占、恢复 Graph。 */
    @Override
    public MainWorkflowResponse confirmProducts(String workflowInstanceId,
                                                ProductConfirmationRequest request,
                                                boolean tokenStreamingEnabled) {
        claimProductConfirmation(workflowInstanceId, request.conversationId());
        return confirmClaimedProducts(workflowInstanceId, request, tokenStreamingEnabled);
    }

    /** 原子抢占 WAITING_CONFIRM 实例，确保并发请求只有一个能够进入恢复链路。 */
    @Override
    public void claimProductConfirmation(String workflowInstanceId, String conversationId) {
        WorkflowInstanceExecutionView instance = workflowExecutionMapper.findInstance(workflowInstanceId);
        validateConfirmationInstance(instance, conversationId, STATUS_WAITING_CONFIRM);
        // 主工作流链路 6：用数据库条件更新抢占确认权，只有一个请求能恢复当前 Checkpoint。
        if (workflowExecutionMapper.claimProductConfirmation(
                workflowInstanceId, conversationId, Instant.now()) != 1) {
            throw new BusinessException(
                    ErrorCode.WORKFLOW_STATE_CONFLICT,
                    "产品确认已被处理，请勿重复提交");
        }
    }

    /** 使用当前请求已经获得的确认权写入产品并恢复 Graph。 */
    @Override
    public MainWorkflowResponse confirmClaimedProducts(String workflowInstanceId,
                                                       ProductConfirmationRequest request,
                                                       boolean tokenStreamingEnabled) {
        WorkflowInstanceExecutionView instance = workflowExecutionMapper.findInstance(workflowInstanceId);
        validateConfirmationInstance(instance, request.conversationId(), STATUS_CONFIRMING);
        try {
            RunnableConfig baseConfig = runnableConfig(workflowInstanceId, request.conversationId());
            StateSnapshot snapshot = mainWorkflowGraph.getState(baseConfig);
            ProductRecallResult recallResult = snapshot.state()
                    .value(MainWorkflowStateKeys.PRODUCT_RECALL_RESULT, ProductRecallResult.class)
                    .orElseThrow(() -> new IllegalStateException("Workflow checkpoint has no product candidates"));
            ProductReferenceResolution resolution = snapshot.state()
                    .value(MainWorkflowStateKeys.PRODUCT_REFERENCE_RESOLUTION, ProductReferenceResolution.class)
                    .orElseThrow(() -> new IllegalStateException("Workflow checkpoint has no product resolution"));
            List<ConfirmedProduct> selectedProducts = selectedProducts(
                    workflowInstanceId, request, recallResult, resolution);

            // 主工作流链路 7：抢占成功后持久化当前会话确认产品，再恢复 Graph。
            confirmedProductService.saveConfirmedProducts(selectedProducts);
            workflowExecutionMapper.updateInstanceStatus(workflowInstanceId, STATUS_RUNNING, null, Instant.now());
            RunnableConfig updatedConfig = mainWorkflowGraph.updateState(
                    snapshot.config(),
                    Map.of(
                            MainWorkflowStateKeys.RESOLVED_PRODUCTS, selectedProducts,
                            MainWorkflowStateKeys.HUMAN_CONFIRM_REQUIRED, false,
                            MainWorkflowStateKeys.TOKEN_STREAMING_ENABLED, tokenStreamingEnabled));
            NodeOutput output = mainWorkflowGraph.invokeAndGetOutput(Map.of(), updatedConfig.withResume())
                    .orElseThrow(() -> new IllegalStateException("Resumed workflow graph returned empty output"));
            if (!output.isEND()) {
                throw new IllegalStateException("Resumed workflow was interrupted again unexpectedly");
            }
            return complete(output.state(), workflowInstanceId, instance.createdAt());
        }
        catch (Exception ex) {
            fail(workflowInstanceId, ex);
            throw new IllegalStateException("Main workflow resume failed", ex);
        }
    }

    /** 后台恢复任务未提交时将 CONFIRMING 安全退回 WAITING_CONFIRM。 */
    @Override
    public void releaseProductConfirmationClaim(String workflowInstanceId, String conversationId) {
        if (workflowExecutionMapper.releaseProductConfirmationClaim(
                workflowInstanceId, conversationId, Instant.now()) != 1) {
            log.warn("[Workflow] action=release-confirmation-claim status=ignored workflowInstanceId={}",
                    workflowInstanceId);
        }
    }

    /**
     * 从主图最新 Checkpoint 恢复仍处于 RUNNING 的工作流。
     *
     * <p>数据库条件更新先把实例原子认领为 RESUMING，防止两个请求同时从同一 Checkpoint
     * 分叉。动态 DAG 再次进入时，各任务子图会复用自己的终态 Checkpoint。</p>
     */
    @Override
    public MainWorkflowResponse resume(String workflowInstanceId, WorkflowResumeRequest request) {
        WorkflowInstanceExecutionView instance = workflowExecutionMapper.findInstance(workflowInstanceId);
        validateResumeInstance(instance, request);
        if (workflowExecutionMapper.claimResume(
                workflowInstanceId, request.conversationId(), Instant.now()) != 1) {
            throw new IllegalStateException("Workflow instance is not available for resume");
        }

        RunnableConfig config = runnableConfig(workflowInstanceId, request.conversationId());
        try {
            StateSnapshot snapshot = mainWorkflowGraph.getState(config);
            MainWorkflowRequest originalRequest = snapshot.state()
                    .value(MainWorkflowStateKeys.REQUEST, MainWorkflowRequest.class)
                    .orElseThrow(() -> new IllegalStateException("Workflow checkpoint has no original request"));
            publishEvent(workflowInstanceId, request.conversationId(), WorkflowSseEventType.STAGE, null,
                    Map.of("status", "RESUMING", "checkpointId",
                            snapshot.config().checkPointId().orElse("")));

            // 主工作流恢复链路 1：从最新主图 Checkpoint 继续；DAG 内 SUCCESS 子任务由独立子图直接复用。
            NodeOutput output = mainWorkflowGraph.invokeAndGetOutput(Map.of(), snapshot.config())
                    .orElseThrow(() -> new IllegalStateException("Resumed workflow graph returned empty output"));
            if (!output.isEND()) {
                StateSnapshot interrupted = mainWorkflowGraph.getState(config);
                return waitingConfirmResponse(
                        interrupted, originalRequest, workflowInstanceId, instance.createdAt());
            }
            return complete(output.state(), workflowInstanceId, instance.createdAt());
        }
        catch (Exception ex) {
            fail(workflowInstanceId, ex);
            throw new IllegalStateException("Main workflow checkpoint resume failed", ex);
        }
    }

    /**
     * 将 Graph 中断快照转换为 WAITING_CONFIRM 响应，同时落库 Checkpoint 编号、候选结果和步骤状态。
     */
    private MainWorkflowResponse waitingConfirmResponse(StateSnapshot snapshot,
                                                        MainWorkflowRequest request,
                                                        String workflowInstanceId,
                                                        Instant startedAt) {
        OverAllState state = snapshot.state();
        ProductRecallDecision decision = state
                .value(MainWorkflowStateKeys.PRODUCT_RECALL_DECISION, ProductRecallDecision.class)
                .orElseThrow(() -> new IllegalStateException("Interrupted workflow has no recall decision"));
        ProductRecallResult recallResult = state
                .value(MainWorkflowStateKeys.PRODUCT_RECALL_RESULT, ProductRecallResult.class)
                .orElseThrow(() -> new IllegalStateException("Interrupted workflow has no recall result"));
        Map<String, String> stepIds = MainWorkflowGraphConfig.workflowStepIds(state);
        String checkpointId = snapshot.config().checkPointId()
                .orElseThrow(() -> new IllegalStateException("Interrupted workflow has no checkpoint id"));
        MainWorkflowResponse response = new MainWorkflowResponse(
                true,
                WORKFLOW_CODE,
                workflowInstanceId,
                checkpointId,
                stepIds,
                request.conversationId(),
                request.message(),
                null,
                null,
                Map.of(),
                null,
                decision,
                recallResult,
                true,
                List.of(),
                null,
                STATUS_WAITING_CONFIRM,
                null,
                null,
                null,
                null,
                null,
                Duration.between(startedAt, Instant.now()).toMillis(),
                startedAt,
                null,
                null);
        String humanStepId = stepIds.get(WorkflowNodeDefinition.HUMAN_CONFIRM_PRODUCT.code());
        workflowExecutionMapper.updateStepWaitingConfirm(humanStepId, toJson(recallResult), Instant.now());
        workflowExecutionMapper.updateInstanceStatus(
                workflowInstanceId, STATUS_WAITING_CONFIRM, toJson(response), Instant.now());
        publishEvent(workflowInstanceId, request.conversationId(), WorkflowSseEventType.HUMAN_CONFIRM,
                WorkflowNodeDefinition.HUMAN_CONFIRM_PRODUCT.code(),
                Map.of(
                        "status", STATUS_WAITING_CONFIRM,
                        "checkpointId", checkpointId,
                        "candidateCount", recallResult.candidates().size(),
                        "candidates", confirmationCandidates(recallResult)));
        workflowEventPublisher.completeSubscribers(workflowInstanceId);
        log.info("[Workflow] code={} action=run status=waiting-confirm workflowInstanceId={} checkpointId={} "
                        + "candidateCount={}",
                WORKFLOW_CODE, workflowInstanceId, checkpointId, recallResult.candidates().size());
        return response;
    }

    /** 只向人工确认事件暴露可展示的脱敏候选字段，不发送完整召回审计或 Graph State。 */
    private List<Map<String, Object>> confirmationCandidates(ProductRecallResult recallResult) {
        return recallResult.candidates().stream()
                .map(candidate -> Map.<String, Object>of(
                        "productCode", candidate.productCode(),
                        "productName", candidate.productName(),
                        "productType", candidate.productType(),
                        "insurerName", candidate.insurerName(),
                        "score", candidate.score(),
                        "matchReason", candidate.matchReason()))
                .toList();
    }

    /**
     * 从 END State 组装最终响应，并依次完成最终会话记忆、工作流终态和 Checkpoint 状态收口。
     */
    private MainWorkflowResponse complete(OverAllState finalState,
                                          String workflowInstanceId,
                                          Instant startedAt) {
        IntentRoutingResult routingResult = requiredState(
                finalState, MainWorkflowStateKeys.INTENT_ROUTING_RESULT,
                IntentRoutingResult.class, "intent result");
        AlignedWorkflowContext alignedContext = requiredState(
                finalState, MainWorkflowStateKeys.ALIGNED_CONTEXT,
                AlignedWorkflowContext.class, "aligned context");
        ProductRecallDecision recallDecision = requiredState(
                finalState, MainWorkflowStateKeys.PRODUCT_RECALL_DECISION,
                ProductRecallDecision.class, "recall decision");
        ProductRecallResult recallResult = finalState
                .value(MainWorkflowStateKeys.PRODUCT_RECALL_RESULT, ProductRecallResult.class)
                .orElse(null);
        WorkflowPlan workflowPlan = requiredState(
                finalState, MainWorkflowStateKeys.WORKFLOW_PLAN,
                WorkflowPlan.class, "workflow plan");
        DagExecutionResult dagExecutionResult = requiredState(
                finalState, MainWorkflowStateKeys.DAG_EXECUTION_RESULT,
                DagExecutionResult.class, "DAG execution result");
        WorkflowSummaryResult summaryResult = requiredState(
                finalState, MainWorkflowStateKeys.SUMMARY_RESULT,
                WorkflowSummaryResult.class, "summary result");
        OutputReviewResult outputReviewResult = requiredState(
                finalState, MainWorkflowStateKeys.OUTPUT_REVIEW_RESULT,
                OutputReviewResult.class, "output review result");
        SubAgentExecutionResult agentResponse = dagExecutionResult.taskResults().stream()
                .filter(task -> task.status() == AgentTaskStatus.SUCCESS)
                .map(task -> task.response())
                .findFirst()
                .orElse(null);
        String finalAnswer = requiredState(
                finalState, MainWorkflowStateKeys.FINAL_ANSWER,
                String.class, "final answer");
        Instant endedAt = Instant.now();
        String workflowStatus = workflowStatus(dagExecutionResult, outputReviewResult);
        String errorMessage = workflowErrorMessage(dagExecutionResult, outputReviewResult);
        MainWorkflowResponse response = new MainWorkflowResponse(
                true,
                WORKFLOW_CODE,
                workflowInstanceId,
                null,
                MainWorkflowGraphConfig.workflowStepIds(finalState),
                alignedContext.conversationId(),
                alignedContext.originalQuestion(),
                alignedContext.topicRelation(),
                alignedContext.rewrittenQuestion(),
                alignedContext.confirmedInformation(),
                routingResult.intent(),
                recallDecision,
                recallResult,
                false,
                alignedContext.resolvedProducts(),
                workflowPlan,
                workflowStatus,
                finalAnswer,
                dagExecutionResult,
                summaryResult,
                outputReviewResult,
                agentResponse,
                Duration.between(startedAt, endedAt).toMillis(),
                startedAt,
                endedAt,
                errorMessage);
        // 主工作流链路 13：收口已审核最终回答、未执行分支、工作流终态和 Checkpoint 保留状态。
        saveFinalConversation(response);
        workflowExecutionMapper.skipPendingSteps(workflowInstanceId, endedAt);
        workflowExecutionMapper.updateInstanceResult(
                workflowInstanceId, workflowStatus, toJson(response), errorMessage, endedAt);
        checkpointSaver.markWorkflowCompleted(workflowInstanceId);
        publishEvent(workflowInstanceId, response.conversationId(), WorkflowSseEventType.COMPLETE, null,
                Map.of(
                        "status", workflowStatus,
                        "finalAnswer", response.finalAnswer(),
                        "durationMs", response.durationMs()));
        workflowEventPublisher.completeSubscribers(workflowInstanceId);
        log.info("[Workflow] code={} action=complete status={} workflowInstanceId={} durationMs={}",
                WORKFLOW_CODE, workflowStatus, workflowInstanceId, response.durationMs());
        return response;
    }

    /**
     * DAG 子任务只保存调用审计；这里将用户原话与聚合后的最终回答作为一次会话交换写入，
     * 保证并行场景下 ai_chat_memory 与 ai_long_term_memory 的内容和顺序一致。
     */
    private void saveFinalConversation(MainWorkflowResponse response) {
        if (!agentMemoryService.isEnabled()) {
            return;
        }
        String invocationId = "wfa-" + UUID.randomUUID().toString().replace("-", "");
        Instant occurredAt = response.endedAt();
        AgentInvocationRecord invocationRecord = new AgentInvocationRecord(
                invocationId,
                response.conversationId(),
                "main-workflow",
                TraceIdUtil.currentTraceId(),
                response.workflowInstanceId(),
                response.workflowStepIds().get(WorkflowNodeDefinition.SUMMARY.code()),
                "openai-compatible",
                modelName(),
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
                occurredAt);
        agentMemoryService.saveSuccessfulExchange(
                new AgentMemoryExchange(
                        response.conversationId(),
                        invocationId,
                        "main-workflow",
                        new UserMessage(response.originalQuestion()),
                        new AssistantMessage(response.finalAnswer()),
                        occurredAt),
                invocationRecord);
    }

    /** 根据输出审核决策和 DAG 成功数量计算工作流终态。 */
    private String workflowStatus(DagExecutionResult result, OutputReviewResult reviewResult) {
        if (reviewResult.decision() == OutputReviewDecision.BLOCK) {
            return "REVIEW_BLOCKED";
        }
        if (result.successCount() == result.taskResults().size()) {
            return "SUCCESS";
        }
        if (result.successCount() > 0) {
            return "PARTIAL_SUCCESS";
        }
        return "FAILED";
    }

    /** 生成全任务失败或审核阻断时写入实例表的错误摘要。 */
    private String workflowErrorMessage(DagExecutionResult result, OutputReviewResult reviewResult) {
        if (reviewResult.decision() == OutputReviewDecision.BLOCK) {
            return "Output review blocked: " + String.join("; ", reviewResult.reasons());
        }
        if (result.successCount() == 0) {
            return "All planned agent tasks failed or were skipped";
        }
        return null;
    }

    /** 读取当前全局 ChatModel 名称，用于主工作流最终调用审计。 */
    private String modelName() {
        if (aiModelProperties.getChat() == null || aiModelProperties.getChat().getOptions() == null) {
            return null;
        }
        return aiModelProperties.getChat().getOptions().getModel();
    }

    /** 校验用户选择属于当前候选集合，并转换为会话范围内的标准确认产品。 */
    private List<ConfirmedProduct> selectedProducts(String workflowInstanceId,
                                                    ProductConfirmationRequest request,
                                                    ProductRecallResult recallResult,
                                                    ProductReferenceResolution resolution) {
        LinkedHashSet<String> selectedCodes = request.selectedProductCodes().stream()
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, ProductCandidate> candidatesByCode = recallResult.candidates().stream()
                .collect(Collectors.toMap(ProductCandidate::productCode, Function.identity()));
        if (!candidatesByCode.keySet().containsAll(selectedCodes)) {
            throw new IllegalArgumentException("selectedProductCodes contains a product outside current candidates");
        }
        Instant confirmedAt = Instant.now();
        String sourceClue = String.join(",", resolution.detectedProductClues());
        return selectedCodes.stream()
                .map(candidatesByCode::get)
                .map(candidate -> new ConfirmedProduct(
                        request.conversationId(),
                        candidate.productCode(),
                        candidate.productName(),
                        candidate.productType(),
                        candidate.insurerName(),
                        sourceClue,
                        recallResult.retrievalCallId(),
                        workflowInstanceId,
                        confirmedAt))
                .toList();
    }

    /** 校验确认请求与工作流实例的 conversationId 和预期状态一致。 */
    private void validateConfirmationInstance(WorkflowInstanceExecutionView instance,
                                               String conversationId,
                                               String expectedStatus) {
        if (instance == null) {
            throw new IllegalArgumentException("workflowInstanceId does not exist");
        }
        if (!instance.conversationId().equals(conversationId)) {
            throw new IllegalArgumentException("conversationId does not match workflow instance");
        }
        if (!expectedStatus.equals(instance.status())) {
            throw new BusinessException(
                    ErrorCode.WORKFLOW_STATE_CONFLICT,
                    "工作流当前不能处理产品确认，status=" + instance.status());
        }
    }

    /** 校验主动恢复只能作用于同会话且仍为 RUNNING 的实例。 */
    private void validateResumeInstance(WorkflowInstanceExecutionView instance,
                                        WorkflowResumeRequest request) {
        if (instance == null) {
            throw new IllegalArgumentException("workflow instance does not exist");
        }
        if (!instance.conversationId().equals(request.conversationId())) {
            throw new IllegalArgumentException("conversationId does not match workflow instance");
        }
        if (!STATUS_RUNNING.equals(instance.status())) {
            throw new IllegalArgumentException("only RUNNING workflow instance can be resumed");
        }
    }

    /** 创建 Graph 调用配置，以 workflowInstanceId 作为 Checkpoint threadId。 */
    private RunnableConfig runnableConfig(String workflowInstanceId, String conversationId) {
        return RunnableConfig.builder()
                .threadId(workflowInstanceId)
                .defaultParallelExecutor(workflowDagTaskExecutor)
                .addMetadata(OceanBaseCheckpointSaver.METADATA_WORKFLOW_INSTANCE_ID, workflowInstanceId)
                .addMetadata(OceanBaseCheckpointSaver.METADATA_CONVERSATION_ID, conversationId)
                .build();
    }

    /** 读取 Graph 必需状态，并在缺失时抛出包含业务含义的异常。 */
    private <T> T requiredState(OverAllState state, String key, Class<T> type, String description) {
        return state.value(key, type)
                .orElseThrow(() -> new IllegalStateException("Main workflow graph returned empty " + description));
    }

    /** 统一收口未处理异常，将实例、待执行步骤和 Checkpoint 标记为失败。 */
    private void fail(String workflowInstanceId, Exception exception) {
        Instant endedAt = Instant.now();
        String errorMessage = truncateErrorMessage(exception);
        WorkflowInstanceExecutionView instance = workflowExecutionMapper.findInstance(workflowInstanceId);
        workflowExecutionMapper.skipPendingSteps(workflowInstanceId, endedAt);
        workflowExecutionMapper.updateInstanceResult(
                workflowInstanceId, "FAILED", null, errorMessage, endedAt);
        checkpointSaver.markWorkflowFailed(workflowInstanceId);
        if (instance != null) {
            publishEvent(workflowInstanceId, instance.conversationId(), WorkflowSseEventType.ERROR, null,
                    Map.of("status", "FAILED", "message", "主工作流执行失败，请稍后重试或联系人工支持"));
        }
        workflowEventPublisher.completeSubscribers(workflowInstanceId);
        log.error("[Workflow] code={} action=execute status=failed workflowInstanceId={}",
                WORKFLOW_CODE, workflowInstanceId, exception);
    }

    /** 将已脱敏的工作流生命周期数据交给 profile 对应的 SSE 发布器。 */
    private void publishEvent(String workflowInstanceId,
                              String conversationId,
                              WorkflowSseEventType type,
                              String node,
                              Map<String, Object> data) {
        workflowEventPublisher.publish(workflowInstanceId, conversationId, type, node, data);
    }

    /** 为当前 Graph 定义中的每个节点预分配数据库步骤编号。 */
    private Map<String, String> createWorkflowSteps() {
        Map<String, String> workflowStepIds = new LinkedHashMap<>();
        for (WorkflowNodeDefinition nodeDefinition : WorkflowNodeDefinition.values()) {
            workflowStepIds.put(nodeDefinition.code(), newWorkflowStepId());
        }
        return workflowStepIds;
    }

    /** 将全部可能执行的 Graph 节点以 PENDING 状态写入步骤审计表。 */
    private void insertWorkflowSteps(String workflowInstanceId,
                                     Map<String, String> workflowStepIds,
                                     String inputJson,
                                     Instant createdAt) {
        for (WorkflowNodeDefinition nodeDefinition : WorkflowNodeDefinition.values()) {
            workflowExecutionMapper.insertStep(new WorkflowStepRecord(
                    workflowStepIds.get(nodeDefinition.code()),
                    workflowInstanceId,
                    nodeDefinition.code(),
                    nodeDefinition.nodeName(),
                    nodeDefinition.type(),
                    nodeDefinition.target(),
                    "PENDING",
                    inputJson,
                    null,
                    createdAt));
        }
    }

    /** 将工作流输入、状态或响应转换为数据库审计 JSON。 */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize workflow payload", ex);
        }
    }

    /** 将异常消息限制到数据库错误字段允许的最大长度。 */
    private String truncateErrorMessage(Exception exception) {
        if (exception == null || exception.getMessage() == null) {
            return null;
        }
        String message = exception.getMessage();
        return message.length() <= 1024 ? message : message.substring(0, 1024);
    }

    /** 生成工作流实例编号。 */
    private String newWorkflowInstanceId() {
        return "wfi-" + UUID.randomUUID().toString().replace("-", "");
    }

    /** 生成工作流节点步骤编号。 */
    private String newWorkflowStepId() {
        return "wfs-" + UUID.randomUUID().toString().replace("-", "");
    }
}
