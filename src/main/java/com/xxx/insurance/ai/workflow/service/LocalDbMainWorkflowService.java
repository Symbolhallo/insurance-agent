package com.xxx.insurance.ai.workflow.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.xxx.insurance.common.security.RequestIdentity;
import com.xxx.insurance.common.security.ResourceAccessService;
import com.xxx.insurance.ai.workflow.config.WorkflowLifecycleProperties;
import com.xxx.insurance.ai.config.AiModelProperties;
import com.xxx.insurance.ai.workflow.config.MainWorkflowGraphConfig;
import com.xxx.insurance.ai.workflow.mapper.WorkflowExecutionMapper;
import com.xxx.insurance.ai.workflow.model.MainWorkflowRequest;
import com.xxx.insurance.ai.workflow.model.MainWorkflowResponse;
import com.xxx.insurance.ai.workflow.model.MainWorkflowStateKeys;
import com.xxx.insurance.ai.workflow.model.ProductReferenceResolution;
import com.xxx.insurance.ai.workflow.model.WorkflowInstanceExecutionView;
import com.xxx.insurance.ai.workflow.model.WorkflowInstanceRecord;
import com.xxx.insurance.ai.workflow.model.WorkflowNodeDefinition;
import com.xxx.insurance.ai.workflow.sse.model.WorkflowSseEventType;
import com.xxx.insurance.ai.workflow.sse.service.WorkflowEventPublisher;
import com.xxx.insurance.ai.workflow.lifecycle.WorkflowFinalizationService;
import com.xxx.insurance.ai.workflow.lifecycle.WorkflowExecutionClaimService;
import com.xxx.insurance.ai.workflow.lifecycle.WorkflowPauseService;
import com.xxx.insurance.ai.workflow.lifecycle.WorkflowStartService;
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
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    private static final long INITIAL_EXECUTION_FENCE_TOKEN = 1L;

    private static final Logger log = LoggerFactory.getLogger(LocalDbMainWorkflowService.class);

    private static final String STATUS_RUNNING = "RUNNING";

    private static final String STATUS_WAITING_CONFIRM = "WAITING_CONFIRM";

    private final WorkflowExecutionMapper workflowExecutionMapper;

    private final CompiledGraph mainWorkflowGraph;

    private final ConversationConfirmedProductService confirmedProductService;

    private final AiModelProperties aiModelProperties;

    private final WorkflowEventPublisher workflowEventPublisher;

    private final WorkflowStartService workflowStartService;

    private final WorkflowFinalizationService workflowFinalizationService;

    private final WorkflowPauseService workflowPauseService;

    private final WorkflowLifecycleProperties lifecycleProperties;

    private final ResourceAccessService resourceAccessService;

    private final MainWorkflowExecutionFactory executionFactory;

    private final MainWorkflowResponseAssembler responseAssembler;

    private final WorkflowExecutionClaimService executionClaimService;

    public LocalDbMainWorkflowService(WorkflowExecutionMapper workflowExecutionMapper,
                                      @Qualifier(MainWorkflowGraphConfig.MAIN_WORKFLOW_GRAPH)
                                      CompiledGraph mainWorkflowGraph,
                                      ConversationConfirmedProductService confirmedProductService,
                                      AiModelProperties aiModelProperties,
                                      WorkflowEventPublisher workflowEventPublisher,
                                      WorkflowStartService workflowStartService,
                                      WorkflowFinalizationService workflowFinalizationService,
                                      WorkflowPauseService workflowPauseService,
                                      WorkflowLifecycleProperties lifecycleProperties,
                                      ResourceAccessService resourceAccessService,
                                      MainWorkflowExecutionFactory executionFactory,
                                      MainWorkflowResponseAssembler responseAssembler,
                                      WorkflowExecutionClaimService executionClaimService) {
        this.workflowExecutionMapper = workflowExecutionMapper;
        this.mainWorkflowGraph = mainWorkflowGraph;
        this.confirmedProductService = confirmedProductService;
        this.aiModelProperties = aiModelProperties;
        this.workflowEventPublisher = workflowEventPublisher;
        this.workflowStartService = workflowStartService;
        this.workflowFinalizationService = workflowFinalizationService;
        this.workflowPauseService = workflowPauseService;
        this.lifecycleProperties = lifecycleProperties;
        this.resourceAccessService = resourceAccessService;
        this.executionFactory = executionFactory;
        this.responseAssembler = responseAssembler;
        this.executionClaimService = executionClaimService;
    }

    /** 生成符合数据库字段长度和日志规范的工作流实例编号。 */
    @Override
    public String createWorkflowInstanceId() {
        return executionFactory.createWorkflowInstanceId();
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
     * 执行一次完整顶层工作流。先序列化请求并预分配步骤编号，在启动事务中取得 conversation 独占锁、
     * 创建 RUNNING 实例和 PENDING 步骤，再发布 START 事实事件；随后以 workflowInstanceId 作为 Checkpoint
     * threadId，把固定 fencing token、步骤映射和 Token 开关写入 State 并执行 Main Graph。Graph 中断时
     * 原子转为 WAITING_CONFIRM 并发送候选事件；到达 END 时统一收口 Memory、Checkpoint、实例终态和
     * COMPLETE Outbox；未处理异常走幂等 FAILED 收口，不能覆盖已经提交的业务终态。
     */
    @Override
    public MainWorkflowResponse run(String workflowInstanceId,
                                    MainWorkflowRequest request,
                                    boolean tokenStreamingEnabled) {
        RequestIdentity identity = request.identity() == null
                ? RequestIdentity.localDefault()
                : request.identity();
        resourceAccessService.claimConversation(identity, request.conversationId());
        Instant startedAt = Instant.now();
        String inputJson = executionFactory.toJson(request);
        Map<String, String> workflowStepIds = executionFactory.createWorkflowSteps();

        log.info("[Workflow] code={} action=run status=start workflowInstanceId={} conversationId={}",
                WORKFLOW_CODE, workflowInstanceId, request.conversationId());
        Instant leaseUntil = startedAt.plus(lifecycleProperties.getExecutionLease());
        WorkflowInstanceRecord instanceRecord = new WorkflowInstanceRecord(
                workflowInstanceId,
                WORKFLOW_CODE,
                request.conversationId(),
                identity.tenantId(),
                identity.userId(),
                identity.customerId(),
                identity.operatorId(),
                request.requestId(),
                TraceIdUtil.currentTraceId(),
                STATUS_RUNNING,
                inputJson,
                lifecycleProperties.getInstanceId(),
                leaseUntil,
                startedAt);
        // 主工作流链路 3：在同一事务内占用 conversation 并持久化实例和步骤；双击或并发消息在数据库层被拒绝。
        // 数据库唯一约束防止同一请求重复提交，以及同一 conversation 并发启动多个顶层工作流。
        workflowStartService.start(
                instanceRecord,
                executionFactory.workflowStepRecords(
                        workflowInstanceId, workflowStepIds, inputJson, startedAt));
        long executionFenceToken = INITIAL_EXECUTION_FENCE_TOKEN;
        publishEvent(workflowInstanceId, request.conversationId(), executionFenceToken,
                WorkflowSseEventType.START, null,
                Map.of("status", STATUS_RUNNING, "workflowCode", WORKFLOW_CODE));

        try {
            // 主工作流链路 4：发布 start 后，以实例 ID 作为 threadId 启动可持久化、可恢复的 Main Graph。
            RunnableConfig graphConfig = executionFactory.runnableConfig(
                    workflowInstanceId, request.conversationId(), executionFenceToken);
            Map<String, Object> initialState = executionFactory.initialState(
                    request, workflowInstanceId, executionFenceToken, workflowStepIds, tokenStreamingEnabled);
            NodeOutput graphOutput = mainWorkflowGraph.invokeAndGetOutput(initialState, graphConfig)
                    .orElseThrow(() -> new IllegalStateException("Main workflow graph returned empty output"));
            if (!graphOutput.isEND()) {
                StateSnapshot snapshot = mainWorkflowGraph.getState(graphConfig);
                return waitingConfirmResponse(
                        snapshot, request, workflowInstanceId, executionFenceToken, startedAt);
            }
            return complete(graphOutput.state(), workflowInstanceId, startedAt);
        }
        catch (Exception ex) {
            fail(workflowInstanceId, executionFenceToken, ex, request);
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

    /**
     * 同步确认入口的完整门面：先 CAS 抢占 WAITING_CONFIRM 并取得新 fencing token，再校验/保存产品、
     * 更新 Checkpoint State 和执行租约，最后从中断点恢复 Graph；流式开关决定恢复后的模型增量是否发布。
     */
    @Override
    public MainWorkflowResponse confirmProducts(String workflowInstanceId,
                                                ProductConfirmationRequest request,
                                                boolean tokenStreamingEnabled) {
        long executionFenceToken = claimProductConfirmation(
                workflowInstanceId, request.conversationId());
        return confirmClaimedProducts(
                workflowInstanceId, request, tokenStreamingEnabled, executionFenceToken);
    }

    /**
     * 校验实例属于当前 conversation 且处于 WAITING_CONFIRM，再以数据库 CAS 将其迁移为 CONFIRMING、
     * 写入当前 JVM owner、短 claim lease 并递增 fencing token；随后回读并验证执行权。并发确认只有一个
     * 请求成功，其余返回 WORKFLOW_STATE_CONFLICT，不能从同一 Checkpoint 启动重复分支。
     */
    @Override
    public long claimProductConfirmation(String workflowInstanceId, String conversationId) {
        return executionClaimService.claimProductConfirmation(workflowInstanceId, conversationId);
    }

    /**
     * 使用调用方已取得的 confirmation fencing token 恢复工作流：回读 Checkpoint 候选和产品解析结果，
     * 拒绝候选集合之外的选择，保存 conversation 范围内标准产品，把实例转回 RUNNING 并续 execution lease，
     * 通过 updateState 写入确认结果/Token 开关，再 withResume 经过人工确认节点继续后续主图。END 后执行
     * 正常事务收口；任何异常进入幂等失败收口，旧 token 无法写状态、Checkpoint 或 SSE 事件。
     */
    @Override
    public MainWorkflowResponse confirmClaimedProducts(String workflowInstanceId,
                                                       ProductConfirmationRequest request,
                                                       boolean tokenStreamingEnabled,
                                                       long executionFenceToken) {
        WorkflowInstanceExecutionView instance = executionClaimService.requireClaimedConfirmation(
                workflowInstanceId, request.conversationId(), executionFenceToken);
        try {
            RunnableConfig baseConfig = executionFactory.runnableConfig(
                    workflowInstanceId, request.conversationId(), executionFenceToken);
            StateSnapshot snapshot = mainWorkflowGraph.getState(baseConfig);
            ProductRecallResult recallResult = snapshot.state()
                    .value(MainWorkflowStateKeys.PRODUCT_RECALL_RESULT, ProductRecallResult.class)
                    .orElseThrow(() -> new IllegalStateException("Workflow checkpoint has no product candidates"));
            ProductReferenceResolution resolution = snapshot.state()
                    .value(MainWorkflowStateKeys.PRODUCT_REFERENCE_RESOLUTION, ProductReferenceResolution.class)
                    .orElseThrow(() -> new IllegalStateException("Workflow checkpoint has no product resolution"));
            List<ConfirmedProduct> selectedProducts = selectedProducts(
                    workflowInstanceId, request, recallResult, resolution);

            // 主工作流链路 9：保存标准产品，更新 Checkpoint State，并通过 withResume 继续执行人工确认节点。
            confirmedProductService.saveConfirmedProducts(selectedProducts);
            Instant now = Instant.now();
            if (workflowExecutionMapper.markRunningAfterConfirmation(
                    workflowInstanceId,
                    lifecycleProperties.getInstanceId(),
                    executionFenceToken,
                    now.plus(lifecycleProperties.getExecutionLease()),
                    now) != 1) {
                throw new IllegalStateException("Product confirmation execution lease was lost");
            }
            RunnableConfig updatedConfig = mainWorkflowGraph.updateState(
                    snapshot.config(),
                    Map.of(
                            MainWorkflowStateKeys.RESOLVED_PRODUCTS, selectedProducts,
                            MainWorkflowStateKeys.HUMAN_CONFIRM_REQUIRED, false,
                            MainWorkflowStateKeys.EXECUTION_FENCE_TOKEN, executionFenceToken,
                            MainWorkflowStateKeys.TOKEN_STREAMING_ENABLED, tokenStreamingEnabled));
            NodeOutput output = mainWorkflowGraph.invokeAndGetOutput(Map.of(), updatedConfig.withResume())
                    .orElseThrow(() -> new IllegalStateException("Resumed workflow graph returned empty output"));
            if (!output.isEND()) {
                throw new IllegalStateException("Resumed workflow was interrupted again unexpectedly");
            }
            return complete(output.state(), workflowInstanceId, instance.createdAt());
        }
        catch (Exception ex) {
            fail(workflowInstanceId, executionFenceToken, ex);
            throw new IllegalStateException("Main workflow resume failed", ex);
        }
    }

    /**
     * 当 SSE 订阅或线程池提交失败时，按 workflow、conversation、owner 和 fencing token 条件把尚未消费的
     * CONFIRMING 抢占退回 WAITING_CONFIRM；CAS 失败说明执行权已转移或恢复已开始，仅记录日志不覆盖新状态。
     */
    @Override
    public void releaseProductConfirmationClaim(String workflowInstanceId,
                                                String conversationId,
                                                long executionFenceToken) {
        executionClaimService.releaseProductConfirmationClaim(
                workflowInstanceId, conversationId, executionFenceToken);
    }

    /**
     * 从主图最新 Checkpoint 恢复仍处于 RUNNING 的工作流。
     *
     * <p>数据库条件更新先把实例原子认领为 RESUMING，防止两个请求同时从同一 Checkpoint
     * 分叉。动态 DAG 再次进入时，各任务子图会复用自己的终态 Checkpoint。</p>
     */
    @Override
    public MainWorkflowResponse resume(String workflowInstanceId, WorkflowResumeRequest request) {
        WorkflowExecutionClaimService.ResumeClaim claim = executionClaimService.claimResume(
                workflowInstanceId, request);
        WorkflowInstanceExecutionView instance = claim.instance();
        long executionFenceToken = claim.executionFenceToken();

        RunnableConfig config = executionFactory.runnableConfig(
                workflowInstanceId, request.conversationId(), executionFenceToken);
        try {
            StateSnapshot snapshot = mainWorkflowGraph.getState(config);
            MainWorkflowRequest originalRequest = snapshot.state()
                    .value(MainWorkflowStateKeys.REQUEST, MainWorkflowRequest.class)
                    .orElseThrow(() -> new IllegalStateException("Workflow checkpoint has no original request"));
            publishEvent(workflowInstanceId, request.conversationId(), executionFenceToken,
                    WorkflowSseEventType.STAGE, null,
                    Map.of("status", "RESUMING", "checkpointId",
                            snapshot.config().checkPointId().orElse("")));

            Instant resumeTime = Instant.now();
            if (workflowExecutionMapper.markRunningAfterResume(
                    workflowInstanceId,
                    lifecycleProperties.getInstanceId(),
                    executionFenceToken,
                    resumeTime.plus(lifecycleProperties.getExecutionLease()),
                    resumeTime) != 1) {
                throw new IllegalStateException("Workflow resume execution lease was lost");
            }

            // 主工作流恢复链路 1：从最新主图 Checkpoint 继续；DAG 内 SUCCESS 子任务由独立子图直接复用。
            RunnableConfig updatedConfig = mainWorkflowGraph.updateState(
                    snapshot.config(),
                    Map.of(MainWorkflowStateKeys.EXECUTION_FENCE_TOKEN, executionFenceToken));
            NodeOutput output = mainWorkflowGraph.invokeAndGetOutput(Map.of(), updatedConfig)
                    .orElseThrow(() -> new IllegalStateException("Resumed workflow graph returned empty output"));
            if (!output.isEND()) {
                StateSnapshot interrupted = mainWorkflowGraph.getState(config);
                return waitingConfirmResponse(
                        interrupted, originalRequest, workflowInstanceId,
                        executionFenceToken, instance.createdAt());
            }
            return complete(output.state(), workflowInstanceId, instance.createdAt());
        }
        catch (Exception ex) {
            fail(workflowInstanceId, executionFenceToken, ex);
            throw new IllegalStateException("Main workflow checkpoint resume failed", ex);
        }
    }

    /**
     * 将 interruptBefore 快照转换为人工确认协议。读取并校验召回决定、候选结果、步骤映射和 checkpointId，
     * 只抽取可展示的脱敏候选构造响应，再调用暂停事务原子更新步骤/实例、延长 conversation 锁并写入
     * HUMAN_CONFIRM Outbox。事务提交后立即从事实表 flush；实际 emitter.send 成功才关闭第一段 SSE，
     * 失败则由数据库 Poller 或 Last-Event-ID 重连补偿，HTTP/Graph 线程无需等待用户选择。
     */
    private MainWorkflowResponse waitingConfirmResponse(StateSnapshot snapshot,
                                                        MainWorkflowRequest request,
                                                        String workflowInstanceId,
                                                        long executionFenceToken,
                                                        Instant startedAt) {
        // 主工作流链路 7：将 interruptBefore 快照转为 WAITING_CONFIRM，发布 human_confirm 后结束本段 SSE。
        MainWorkflowResponse response = responseAssembler.waitingConfirmation(
                snapshot, request, workflowInstanceId, startedAt);
        ProductRecallResult recallResult = response.productRecallResult();
        String checkpointId = response.checkpointId();
        String humanStepId = response.workflowStepIds()
                .get(WorkflowNodeDefinition.HUMAN_CONFIRM_PRODUCT.code());
        Instant waitingAt = Instant.now();
        workflowPauseService.pauseForProductConfirmation(
                workflowInstanceId,
                request.conversationId(),
                executionFenceToken,
                humanStepId,
                executionFactory.toJson(response),
                executionFactory.toJson(recallResult),
                WorkflowNodeDefinition.HUMAN_CONFIRM_PRODUCT.code(),
                Map.of(
                        "status", STATUS_WAITING_CONFIRM,
                        "checkpointId", checkpointId,
                        "candidateCount", recallResult.candidates().size(),
                        "candidates", responseAssembler.confirmationCandidates(recallResult)),
                waitingAt);
        // 主工作流链路 7.1：暂停事务提交后立即读取事实表；human_confirm 发送成功后自动关闭本段 SSE。
        // 若本次读取失败则保留订阅，由数据库 Poller 继续补偿，禁止在事件送达前强制关闭连接。
        workflowEventPublisher.flushPersistedEvents(workflowInstanceId);
        log.info("[Workflow] code={} action=run status=waiting-confirm workflowInstanceId={} checkpointId={} "
                        + "candidateCount={}",
                WORKFLOW_CODE, workflowInstanceId, checkpointId, recallResult.candidates().size());
        return response;
    }

    /**
     * 从 END State 强类型读取对齐、路由、召回、计划、DAG、Summary、Review 和唯一 finalAnswer，计算
     * SUCCESS/PARTIAL_SUCCESS/REVIEW_BLOCKED/FAILED 业务终态并组装响应。随后用 State 中固定 fencing token
     * 调用最终收口事务，原子写实例终态、最终 Memory、步骤、Checkpoint、COMPLETE Outbox 和释放会话锁；
     * 事务提交后立即 flush，发送失败仍可由 Poller/Last-Event-ID 补偿。重复收口只记录幂等忽略日志。
     */
    private MainWorkflowResponse complete(OverAllState finalState,
                                          String workflowInstanceId,
                                          Instant startedAt) {
        MainWorkflowResponseAssembler.CompletedWorkflow completed = responseAssembler.completed(
                finalState, workflowInstanceId, startedAt);
        MainWorkflowResponse response = completed.response();
        // 主工作流链路 17：终态、最终 Memory、Checkpoint、SSE Outbox 和会话锁在同一事务收口。
        boolean finalized = workflowFinalizationService.complete(
                response, executionFactory.toJson(response), modelName(), completed.executionFenceToken(),
                completed.originalRequest().identity() == null
                        ? RequestIdentity.localDefault()
                        : completed.originalRequest().identity());
        if (!finalized) {
            log.info("[Workflow] code={} action=complete status=idempotent-ignore workflowInstanceId={}",
                    WORKFLOW_CODE, workflowInstanceId);
        }
        workflowFinalizationService.flushEvents(workflowInstanceId);
        log.info("[Workflow] code={} action=complete status={} workflowInstanceId={} durationMs={}",
                WORKFLOW_CODE, response.status(), workflowInstanceId, response.durationMs());
        return response;
    }

    /** 读取当前全局 AgentScope Model 名称，用于主工作流最终调用审计。 */
    private String modelName() {
        return aiModelProperties.getModelName();
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

    /**
     * 统一处理主图外围未捕获异常：截断数据库错误摘要，使用当前 fencing token 尝试把非终态实例、待执行
     * 步骤和 Checkpoint 原子收口为 FAILED，并写 ERROR Outbox/释放 conversation 锁；提交成功后立即 flush。
     * 若实例已经 SUCCESS、PARTIAL_SUCCESS 或 REVIEW_BLOCKED，则失败 CAS 返回 false，只记录迟到异常，
     * 绝不覆盖已提交终态。
     */
    private void fail(String workflowInstanceId, long executionFenceToken, Exception exception) {
        fail(workflowInstanceId, executionFenceToken, exception, null);
    }

    private void fail(String workflowInstanceId,
                      long executionFenceToken,
                      Exception exception,
                      MainWorkflowRequest originalRequest) {
        Instant endedAt = Instant.now();
        String errorMessage = executionFactory.truncateErrorMessage(exception);
        boolean unsupported = hasCause(exception, UnsupportedWorkflowIntentException.class);
        String frontendMessage = unsupported
                ? UnsupportedWorkflowIntentException.USER_MESSAGE
                : null;
        WorkflowInstanceExecutionView instance = workflowExecutionMapper.findInstance(workflowInstanceId);
        boolean failed = false;
        if (instance != null) {
            if (unsupported && originalRequest != null) {
                failed = workflowFinalizationService.failUnsupported(
                        workflowInstanceId, instance.conversationId(), errorMessage, frontendMessage,
                        originalRequest.message(), modelName(), originalRequest.identity(),
                        executionFenceToken, endedAt);
            }
            else {
                failed = workflowFinalizationService.fail(
                        workflowInstanceId, instance.conversationId(), errorMessage,
                        frontendMessage, executionFenceToken, endedAt);
            }
        }
        if (!failed) {
            log.warn("[Workflow] code={} action=fail status=terminal-preserved workflowInstanceId={}",
                    WORKFLOW_CODE, workflowInstanceId);
        }
        else {
            workflowFinalizationService.flushEvents(workflowInstanceId);
        }
        log.error("[Workflow] code={} action=execute status=failed workflowInstanceId={}",
                WORKFLOW_CODE, workflowInstanceId, exception);
    }

    private boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 发布执行期工作流事件的统一外层入口。调用 local-db Publisher 后，会在实例级并发锁内以当前 JVM
     * owner、executionFenceToken 和未过期 lease 做数据库 CAS，原子分配工作流 sequence，将脱敏 data
     * 序列化为带 expireAt 的 OceanBase 事件事实，再从事实表按 sequence 尝试投递本 JVM SseEmitter；
     * 其他实例连接由定时 Poller 获取，发送失败保留数据库事实供 Poller/Last-Event-ID 重放，且不会回滚
     * 已成功的业务 Graph。非 local-db Profile 使用 NoOp Publisher，不产生持久化或网络副作用。
     */
    private void publishEvent(String workflowInstanceId,
                              String conversationId,
                              long executionFenceToken,
                              WorkflowSseEventType type,
                              String node,
                              Map<String, Object> data) {
        workflowEventPublisher.publish(
                workflowInstanceId, conversationId, executionFenceToken, type, node, data);
    }

}
