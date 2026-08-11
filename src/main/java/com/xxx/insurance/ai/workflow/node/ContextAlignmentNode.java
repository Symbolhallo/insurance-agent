package com.xxx.insurance.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.xxx.insurance.ai.agent.AgentTokenStreamContext;
import com.xxx.insurance.ai.workflow.model.AlignedWorkflowContext;
import com.xxx.insurance.ai.workflow.model.MainWorkflowRequest;
import com.xxx.insurance.ai.workflow.model.MainWorkflowStateKeys;
import com.xxx.insurance.ai.workflow.model.ProductReferenceResolution;
import com.xxx.insurance.ai.workflow.service.ContextAlignmentService;
import com.xxx.insurance.product.model.ConfirmedProduct;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 合并请求上下文加载、记忆加载和问题理解的 V1 上下文对齐节点。
 */
@Component
public class ContextAlignmentNode implements NodeAction {

    private final ContextAlignmentService contextAlignmentService;

    public ContextAlignmentNode(ContextAlignmentService contextAlignmentService) {
        this.contextAlignmentService = contextAlignmentService;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        MainWorkflowRequest request = state.value(MainWorkflowStateKeys.REQUEST, MainWorkflowRequest.class)
                .orElseThrow(() -> new IllegalStateException("Missing main workflow request in graph state"));
        ProductReferenceResolution productResolution = state
                .value(MainWorkflowStateKeys.PRODUCT_REFERENCE_RESOLUTION, ProductReferenceResolution.class)
                .orElseThrow(() -> new IllegalStateException("Missing product reference resolution in graph state"));
        List<ConfirmedProduct> resolvedProducts = resolvedProducts(state);
        ProductReferenceResolution effectiveResolution = new ProductReferenceResolution(
                productResolution.conversationId(),
                productResolution.currentQuery(),
                productResolution.conversationConfirmedProducts(),
                productResolution.detectedProductClues(),
                productResolution.productRecallDecision(),
                resolvedProducts);
        // 主工作流链路 11：结合已解析或人工确认的标准产品与会话历史，完成话题对齐和问题改写。
        AlignedWorkflowContext context = contextAlignmentService.align(
                request,
                effectiveResolution,
                streamContext(state, request.conversationId()));
        return Map.of(MainWorkflowStateKeys.ALIGNED_CONTEXT, context);
    }

    /** 仅在 SSE 运行中创建上下文对齐模型的 Token 发布上下文。 */
    private AgentTokenStreamContext streamContext(OverAllState state, String conversationId) {
        boolean enabled = state.value(MainWorkflowStateKeys.TOKEN_STREAMING_ENABLED, Boolean.class).orElse(false);
        if (!enabled) {
            return null;
        }
        String workflowInstanceId = state.value(MainWorkflowStateKeys.WORKFLOW_INSTANCE_ID, String.class)
                .orElseThrow(() -> new IllegalStateException("Missing workflow instance id in graph state"));
        return new AgentTokenStreamContext(
                workflowInstanceId,
                conversationId,
                state.value(MainWorkflowStateKeys.EXECUTION_FENCE_TOKEN, Number.class)
                        .map(Number::longValue)
                        .orElseThrow(() -> new IllegalStateException("Missing execution fence token")),
                null,
                "context-alignment-model",
                AgentTokenStreamContext.PHASE_CONTEXT_ALIGNMENT);
    }

    @SuppressWarnings("unchecked")
    private List<ConfirmedProduct> resolvedProducts(OverAllState state) {
        return state.value(MainWorkflowStateKeys.RESOLVED_PRODUCTS, List.class)
                .map(value -> (List<ConfirmedProduct>) value)
                .orElse(List.of());
    }
}
