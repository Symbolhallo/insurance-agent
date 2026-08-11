package com.xxx.insurance.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.xxx.insurance.ai.workflow.agent.WorkflowSummaryAgent;
import com.xxx.insurance.ai.workflow.model.DagExecutionResult;
import com.xxx.insurance.ai.workflow.model.MainWorkflowStateKeys;
import com.xxx.insurance.ai.workflow.model.AlignedWorkflowContext;
import com.xxx.insurance.ai.workflow.model.WorkflowSummaryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 根据 DAG 任务数量选择直接透传或模型汇总的结果节点。
 */
@Component
public class SummaryNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(SummaryNode.class);

    private final WorkflowSummaryAgent workflowSummaryAgent;

    /** 创建汇总节点并注入负责策略选择与模型调用的 Summary Agent。 */
    public SummaryNode(WorkflowSummaryAgent workflowSummaryAgent) {
        this.workflowSummaryAgent = workflowSummaryAgent;
    }

    /**
     * 读取 DAG 执行结果，生成唯一待审核答案并写入 Graph State。
     */
    @Override
    public Map<String, Object> apply(OverAllState state) {
        DagExecutionResult dagResult = state.value(
                        MainWorkflowStateKeys.DAG_EXECUTION_RESULT,
                        DagExecutionResult.class)
                .orElseThrow(() -> new IllegalStateException("Missing DAG execution result in graph state"));
        boolean tokenStreamingEnabled = state
                .value(MainWorkflowStateKeys.TOKEN_STREAMING_ENABLED, Boolean.class)
                .orElse(false);
        String workflowInstanceId = state
                .value(MainWorkflowStateKeys.WORKFLOW_INSTANCE_ID, String.class)
                .orElseThrow(() -> new IllegalStateException("Missing workflow instance id in graph state"));
        AlignedWorkflowContext alignedContext = state
                .value(MainWorkflowStateKeys.ALIGNED_CONTEXT, AlignedWorkflowContext.class)
                .orElseThrow(() -> new IllegalStateException("Missing aligned context in graph state"));
        long executionFenceToken = state
                .value(MainWorkflowStateKeys.EXECUTION_FENCE_TOKEN, Number.class)
                .map(Number::longValue)
                .orElseThrow(() -> new IllegalStateException("Missing execution fence token in graph state"));
        // 主工作流链路 15：单任务直接透传，多任务调用 Summary Agent 汇总为唯一待审核答案。
        WorkflowSummaryResult summaryResult = workflowSummaryAgent.summarize(
                dagResult,
                tokenStreamingEnabled,
                workflowInstanceId,
                alignedContext.conversationId(),
                executionFenceToken);
        log.info("[Workflow] node=summary action=complete summaryId={} modelInvoked={} sourceTaskCount={}",
                summaryResult.summaryId(), summaryResult.modelInvoked(), summaryResult.sourceTaskCount());
        return Map.of(MainWorkflowStateKeys.SUMMARY_RESULT, summaryResult);
    }
}
