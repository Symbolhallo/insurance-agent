package com.xxx.insurance.ai.workflow.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.xxx.insurance.ai.workflow.config.MainWorkflowGraphConfig;
import com.xxx.insurance.ai.workflow.model.AlignedWorkflowContext;
import com.xxx.insurance.ai.workflow.model.AgentTaskStatus;
import com.xxx.insurance.ai.workflow.model.DagExecutionResult;
import com.xxx.insurance.ai.workflow.model.IntentRoutingResult;
import com.xxx.insurance.ai.workflow.model.MainWorkflowRequest;
import com.xxx.insurance.ai.workflow.model.MainWorkflowResponse;
import com.xxx.insurance.ai.workflow.model.MainWorkflowStateKeys;
import com.xxx.insurance.ai.workflow.model.OutputReviewDecision;
import com.xxx.insurance.ai.workflow.model.OutputReviewResult;
import com.xxx.insurance.ai.workflow.model.ProductRecallDecision;
import com.xxx.insurance.ai.workflow.model.SubAgentExecutionResult;
import com.xxx.insurance.ai.workflow.model.WorkflowPlan;
import com.xxx.insurance.ai.workflow.model.WorkflowSummaryResult;
import com.xxx.insurance.product.model.ProductCandidate;
import com.xxx.insurance.product.model.ProductRecallResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 把 Graph State 转换成稳定的主工作流 API 响应，不执行数据库状态迁移或事件发送。
 *
 * <p>人工确认和最终响应共用这里的强类型 State 读取规则，应用服务只负责在响应组装后调用暂停或
 * 收口事务，避免 DTO 映射、状态计算与生命周期副作用混在同一大方法中。</p>
 */
@Component
public class MainWorkflowResponseAssembler {

    /** 将 interruptBefore 快照转换为等待产品确认的 API 响应。 */
    public MainWorkflowResponse waitingConfirmation(StateSnapshot snapshot,
                                                     MainWorkflowRequest request,
                                                     String workflowInstanceId,
                                                     Instant startedAt) {
        OverAllState state = snapshot.state();
        ProductRecallDecision decision = requiredState(
                state, MainWorkflowStateKeys.PRODUCT_RECALL_DECISION,
                ProductRecallDecision.class, "recall decision");
        ProductRecallResult recallResult = requiredState(
                state, MainWorkflowStateKeys.PRODUCT_RECALL_RESULT,
                ProductRecallResult.class, "recall result");
        String checkpointId = snapshot.config().checkPointId()
                .orElseThrow(() -> new IllegalStateException("Interrupted workflow has no checkpoint id"));
        return new MainWorkflowResponse(
                true,
                MainWorkflowService.WORKFLOW_CODE,
                workflowInstanceId,
                checkpointId,
                MainWorkflowGraphConfig.workflowStepIds(state),
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
                "WAITING_CONFIRM",
                null,
                null,
                null,
                null,
                null,
                Duration.between(startedAt, Instant.now()).toMillis(),
                startedAt,
                null,
                null);
    }

    /**
     * 从 END State 组装最终响应，并返回事务收口仍需使用的原请求与 fencing token。
     */
    public CompletedWorkflow completed(OverAllState finalState,
                                       String workflowInstanceId,
                                       Instant startedAt) {
        MainWorkflowRequest originalRequest = requiredState(
                finalState, MainWorkflowStateKeys.REQUEST,
                MainWorkflowRequest.class, "original request");
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
        long executionFenceToken = finalState
                .value(MainWorkflowStateKeys.EXECUTION_FENCE_TOKEN, Number.class)
                .map(Number::longValue)
                .orElseThrow(() -> new IllegalStateException(
                        "Main workflow graph returned empty execution fence token"));
        Instant endedAt = Instant.now();
        String status = workflowStatus(dagExecutionResult, outputReviewResult);
        MainWorkflowResponse response = new MainWorkflowResponse(
                true,
                MainWorkflowService.WORKFLOW_CODE,
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
                status,
                finalAnswer,
                dagExecutionResult,
                summaryResult,
                outputReviewResult,
                agentResponse,
                Duration.between(startedAt, endedAt).toMillis(),
                startedAt,
                endedAt,
                workflowErrorMessage(dagExecutionResult, outputReviewResult));
        return new CompletedWorkflow(response, originalRequest, executionFenceToken);
    }

    /** 只向人工确认事件暴露可展示的脱敏候选字段。 */
    public List<Map<String, Object>> confirmationCandidates(ProductRecallResult recallResult) {
        return recallResult.candidates().stream()
                .map(this::confirmationCandidate)
                .toList();
    }

    private Map<String, Object> confirmationCandidate(ProductCandidate candidate) {
        return Map.of(
                "productCode", candidate.productCode(),
                "productName", candidate.productName(),
                "productType", candidate.productType(),
                "insurerName", candidate.insurerName(),
                "score", candidate.score(),
                "matchReason", candidate.matchReason());
    }

    private String workflowStatus(DagExecutionResult result, OutputReviewResult reviewResult) {
        if (reviewResult.decision() == OutputReviewDecision.BLOCK) {
            return "REVIEW_BLOCKED";
        }
        if (result.successCount() == result.taskResults().size()) {
            return "SUCCESS";
        }
        return result.successCount() > 0 ? "PARTIAL_SUCCESS" : "FAILED";
    }

    private String workflowErrorMessage(DagExecutionResult result, OutputReviewResult reviewResult) {
        if (reviewResult.decision() == OutputReviewDecision.BLOCK) {
            return "Output review blocked: " + String.join("; ", reviewResult.reasons());
        }
        return result.successCount() == 0 ? "All planned agent tasks failed or were skipped" : null;
    }

    private <T> T requiredState(OverAllState state, String key, Class<T> type, String description) {
        return state.value(key, type)
                .orElseThrow(() -> new IllegalStateException("Main workflow graph returned empty " + description));
    }

    /** 最终响应及其事务收口上下文。 */
    public record CompletedWorkflow(
            MainWorkflowResponse response,
            MainWorkflowRequest originalRequest,
            long executionFenceToken) {
    }
}
