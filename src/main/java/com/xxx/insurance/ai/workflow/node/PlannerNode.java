package com.xxx.insurance.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.xxx.insurance.ai.agent.AgentTokenStreamContext;
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
        // 主工作流链路 13：调用 Planner Agent 生成受白名单约束的结构化执行计划。
        WorkflowPlan plan = workflowPlannerAgent.plan(context, routingResult, streamContext(state, context));
        return Map.of(MainWorkflowStateKeys.WORKFLOW_PLAN, plan);
    }

    /** 仅在 SSE 运行中创建 Planner ReactAgent 的 Token 发布上下文。 */
    private AgentTokenStreamContext streamContext(OverAllState state, AlignedWorkflowContext context) {
        boolean enabled = state.value(MainWorkflowStateKeys.TOKEN_STREAMING_ENABLED, Boolean.class).orElse(false);
        if (!enabled) {
            return null;
        }
        String workflowInstanceId = state.value(MainWorkflowStateKeys.WORKFLOW_INSTANCE_ID, String.class)
                .orElseThrow(() -> new IllegalStateException("Missing workflow instance id in graph state"));
        return new AgentTokenStreamContext(
                workflowInstanceId,
                context.conversationId(),
                null,
                WorkflowPlannerAgent.AGENT_NAME,
                AgentTokenStreamContext.PHASE_PLANNER);
    }
}
