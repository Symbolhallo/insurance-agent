package com.xxx.insurance.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.xxx.insurance.ai.workflow.config.MainWorkflowGraphConfig;
import com.xxx.insurance.ai.workflow.model.AlignedWorkflowContext;
import com.xxx.insurance.ai.workflow.model.DagExecutionResult;
import com.xxx.insurance.ai.workflow.model.IntentRoutingResult;
import com.xxx.insurance.ai.workflow.model.MainWorkflowStateKeys;
import com.xxx.insurance.ai.workflow.model.WorkflowNodeDefinition;
import com.xxx.insurance.ai.workflow.model.WorkflowPlan;
import com.xxx.insurance.ai.workflow.service.WorkflowDagExecutor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Main Graph 中的动态 DAG 执行节点。
 */
@Component
public class DagExecutorNode implements NodeAction {

    private final WorkflowDagExecutor workflowDagExecutor;

    /**
     * 创建 Graph 节点适配器，将 StateGraph NodeAction 委托给动态 DAG 执行服务。
     */
    public DagExecutorNode(WorkflowDagExecutor workflowDagExecutor) {
        this.workflowDagExecutor = workflowDagExecutor;
    }

    /**
     * 从 Graph State 读取对齐上下文、意图路由和 Planner 计划，执行后只写入聚合结果。
     */
    @Override
    public Map<String, Object> apply(OverAllState state) {
        AlignedWorkflowContext context = state
                .value(MainWorkflowStateKeys.ALIGNED_CONTEXT, AlignedWorkflowContext.class)
                .orElseThrow(() -> new IllegalStateException("Missing aligned context in graph state"));
        IntentRoutingResult routingResult = state
                .value(MainWorkflowStateKeys.INTENT_ROUTING_RESULT, IntentRoutingResult.class)
                .orElseThrow(() -> new IllegalStateException("Missing intent routing result in graph state"));
        WorkflowPlan workflowPlan = state.value(MainWorkflowStateKeys.WORKFLOW_PLAN, WorkflowPlan.class)
                .orElseThrow(() -> new IllegalStateException("Missing workflow plan in graph state"));
        String workflowInstanceId = state
                .value(MainWorkflowStateKeys.WORKFLOW_INSTANCE_ID, String.class)
                .orElse(null);
        String workflowStepId = MainWorkflowGraphConfig.workflowStepIds(state)
                .get(WorkflowNodeDefinition.DAG_EXECUTOR.code());
        long executionFenceToken = state
                .value(MainWorkflowStateKeys.EXECUTION_FENCE_TOKEN, Number.class)
                .map(Number::longValue)
                .orElseThrow(() -> new IllegalStateException("Missing execution fence token in graph state"));
        boolean tokenStreamingEnabled = state
                .value(MainWorkflowStateKeys.TOKEN_STREAMING_ENABLED, Boolean.class)
                .orElse(false);

        // 主工作流链路 14：执行 Planner DAG；任一任务完成即释放后继，失败只影响其依赖分支。
        DagExecutionResult result = workflowDagExecutor.execute(
                workflowPlan,
                routingResult,
                context,
                workflowInstanceId,
                executionFenceToken,
                workflowStepId,
                tokenStreamingEnabled);
        return Map.of(MainWorkflowStateKeys.DAG_EXECUTION_RESULT, result);
    }
}
