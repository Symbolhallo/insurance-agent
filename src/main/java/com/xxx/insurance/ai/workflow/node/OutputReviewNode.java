package com.xxx.insurance.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.xxx.insurance.ai.workflow.client.OutputReviewGateway;
import com.xxx.insurance.ai.workflow.model.AlignedWorkflowContext;
import com.xxx.insurance.ai.workflow.model.DagExecutionResult;
import com.xxx.insurance.ai.workflow.model.MainWorkflowStateKeys;
import com.xxx.insurance.ai.workflow.model.OutputReviewRequest;
import com.xxx.insurance.ai.workflow.model.OutputReviewResult;
import com.xxx.insurance.ai.workflow.model.WorkflowSummaryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 调用行内成熟输出审核节点的 Main Graph 节点。
 *
 * <p>该节点不实现审核规则，只负责读取 Summary 候选答案、调用 Gateway、校验返回合同并写入 State。
 * Gateway 异常或返回非法结果时直接抛出，阻止未审核内容写入 finalAnswer。</p>
 */
@Component
public class OutputReviewNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(OutputReviewNode.class);

    private final OutputReviewGateway outputReviewGateway;

    /** 创建输出审核节点并注入行内 Gateway。 */
    public OutputReviewNode(OutputReviewGateway outputReviewGateway) {
        this.outputReviewGateway = outputReviewGateway;
    }

    /**
     * 从 Graph State 读取 Summary、DAG 与对齐上下文，调用一次行内审核方法并写入最终答案。
     */
    @Override
    public Map<String, Object> apply(OverAllState state) {
        DagExecutionResult dagResult = state
                .value(MainWorkflowStateKeys.DAG_EXECUTION_RESULT, DagExecutionResult.class)
                .orElseThrow(() -> new IllegalStateException("Missing DAG execution result in graph state"));
        WorkflowSummaryResult summaryResult = state
                .value(MainWorkflowStateKeys.SUMMARY_RESULT, WorkflowSummaryResult.class)
                .orElseThrow(() -> new IllegalStateException("Missing summary result in graph state"));
        AlignedWorkflowContext context = state
                .value(MainWorkflowStateKeys.ALIGNED_CONTEXT, AlignedWorkflowContext.class)
                .orElseThrow(() -> new IllegalStateException("Missing aligned context in graph state"));
        String workflowInstanceId = state
                .value(MainWorkflowStateKeys.WORKFLOW_INSTANCE_ID, String.class)
                .orElseThrow(() -> new IllegalStateException("Missing workflow instance id in graph state"));
        String reviewRequestId = newReviewRequestId();
        OutputReviewRequest request = new OutputReviewRequest(
                reviewRequestId,
                workflowInstanceId,
                context.conversationId(),
                context.originalQuestion(),
                context.rewrittenQuestion(),
                summaryResult.answer(),
                dagResult.taskResults());

        // 主工作流链路 12：审核 Summary 的唯一候选答案；只有 publishableAnswer 能写入最终输出。
        OutputReviewResult result = outputReviewGateway.review(request);
        validateResult(reviewRequestId, result);
        log.info("[Workflow] node=output-review action=complete workflowInstanceId={} reviewRequestId={} decision={}",
                workflowInstanceId, reviewRequestId, result.decision());
        return Map.of(
                MainWorkflowStateKeys.OUTPUT_REVIEW_RESULT, result,
                MainWorkflowStateKeys.FINAL_ANSWER, result.publishableAnswer());
    }

    /** 校验行内响应与请求关联一致，并确保存在唯一可发布文本。 */
    private void validateResult(String reviewRequestId, OutputReviewResult result) {
        Objects.requireNonNull(result, "Output review result must not be null");
        if (!reviewRequestId.equals(result.reviewRequestId())
                || result.decision() == null
                || !StringUtils.hasText(result.publishableAnswer())
                || result.reasons() == null
                || result.durationMs() < 0
                || result.reviewedAt() == null) {
            throw new IllegalStateException("Output review gateway returned invalid result");
        }
    }

    /** 生成单次输出审核请求编号。 */
    private String newReviewRequestId() {
        return "orr-" + UUID.randomUUID().toString().replace("-", "");
    }
}
