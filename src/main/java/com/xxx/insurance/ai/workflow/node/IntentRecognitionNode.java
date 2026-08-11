package com.xxx.insurance.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.xxx.insurance.ai.agent.AgentTokenStreamContext;
import com.xxx.insurance.ai.workflow.model.AlignedWorkflowContext;
import com.xxx.insurance.ai.workflow.model.IntentRoutingResult;
import com.xxx.insurance.ai.workflow.model.MainWorkflowStateKeys;
import com.xxx.insurance.ai.workflow.service.IntentRecognitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * V1 意图识别节点。
 */
@Component
public class IntentRecognitionNode implements NodeAction {

    public static final String PRODUCT_ANALYSIS_INTENT = "PRODUCT_ANALYSIS";

    public static final String KNOWLEDGE_QA_INTENT = "KNOWLEDGE_QA";

    public static final String POLICY_QUERY_INTENT = "POLICY_QUERY";

    public static final String ASSET_QUERY_INTENT = "ASSET_QUERY";

    public static final String MULTI_INTENT = "MULTI_INTENT";

    private static final Logger log = LoggerFactory.getLogger(IntentRecognitionNode.class);

    private final IntentRecognitionService intentRecognitionService;

    public IntentRecognitionNode(IntentRecognitionService intentRecognitionService) {
        this.intentRecognitionService = intentRecognitionService;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        AlignedWorkflowContext context = state
                .value(MainWorkflowStateKeys.ALIGNED_CONTEXT, AlignedWorkflowContext.class)
                .orElseThrow(() -> new IllegalStateException("Missing aligned context in graph state"));
        // 主工作流链路 12：基于对齐后的问题识别业务意图，并确定目标子智能体。
        IntentRoutingResult result = intentRecognitionService.recognize(context, streamContext(state, context));
        log.info("[Workflow] node=intent-recognition action=recognize conversationId={} intent={} targetAgent={}",
                context.conversationId(),
                result.intent(),
                result.targetAgent());
        return Map.of(MainWorkflowStateKeys.INTENT_ROUTING_RESULT, result);
    }

    /** 仅在 SSE 运行中创建意图识别模型的 Token 发布上下文。 */
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
                state.value(MainWorkflowStateKeys.EXECUTION_FENCE_TOKEN, Number.class)
                        .map(Number::longValue)
                        .orElseThrow(() -> new IllegalStateException("Missing execution fence token")),
                null,
                "intent-recognition-model",
                AgentTokenStreamContext.PHASE_INTENT_RECOGNITION);
    }
}
