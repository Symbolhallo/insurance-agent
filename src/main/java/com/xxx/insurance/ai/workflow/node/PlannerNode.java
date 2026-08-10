package com.xxx.insurance.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.xxx.insurance.ai.workflow.agent.WorkflowPlannerAgent;
import com.xxx.insurance.ai.workflow.model.AlignedWorkflowContext;
import com.xxx.insurance.ai.workflow.model.IntentRoutingResult;
import com.xxx.insurance.ai.workflow.model.MainWorkflowStateKeys;
import com.xxx.insurance.ai.workflow.model.WorkflowPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 调用 Planner Agent 并把结构化计划写回 Graph state。
 */
@Component
public class PlannerNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(PlannerNode.class);

    private final WorkflowPlannerAgent workflowPlannerAgent;

    public PlannerNode(WorkflowPlannerAgent workflowPlannerAgent) {
        this.workflowPlannerAgent = workflowPlannerAgent;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        AlignedWorkflowContext context = state
                .value(MainWorkflowStateKeys.ALIGNED_CONTEXT, AlignedWorkflowContext.class)
                .orElseThrow(() -> new IllegalStateException("Missing aligned context in graph state"));
        IntentRoutingResult routingResult = state
                .value(MainWorkflowStateKeys.INTENT_ROUTING_RESULT, IntentRoutingResult.class)
                .orElseThrow(() -> new IllegalStateException("Missing intent routing result in graph state"));
        log.info("[Workflow] node=planner action=plan conversationId={} intent={}",
                context.conversationId(),
                routingResult.intent());
        // 主工作流链路 9：调用 Planner Agent 生成受白名单约束的结构化执行计划。
        WorkflowPlan plan = workflowPlannerAgent.plan(context, routingResult);
        return Map.of(MainWorkflowStateKeys.WORKFLOW_PLAN, plan);
    }
}
