package com.xxx.insurance.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.xxx.insurance.ai.agent.AgentTokenStreamContext;
import com.xxx.insurance.ai.workflow.model.MainWorkflowRequest;
import com.xxx.insurance.ai.workflow.model.MainWorkflowStateKeys;
import com.xxx.insurance.ai.workflow.model.ProductReferenceResolution;
import com.xxx.insurance.ai.workflow.service.ProductReferenceResolutionService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Main Graph 首个业务节点：识别当前输入中的产品线索并解析会话已确认产品。
 */
@Component
public class ProductReferenceResolutionNode implements NodeAction {

    private final ProductReferenceResolutionService resolutionService;

    public ProductReferenceResolutionNode(ProductReferenceResolutionService resolutionService) {
        this.resolutionService = resolutionService;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        MainWorkflowRequest request = state.value(MainWorkflowStateKeys.REQUEST, MainWorkflowRequest.class)
                .orElseThrow(() -> new IllegalStateException("Missing main workflow request in graph state"));
        // 主工作流链路 4：仅加载当前 conversationId 的确认产品，识别线索并决定是否进入候选确认分支。
        ProductReferenceResolution resolution = resolutionService.resolve(request, streamContext(state, request));
        return Map.of(
                MainWorkflowStateKeys.PRODUCT_REFERENCE_RESOLUTION, resolution,
                MainWorkflowStateKeys.PRODUCT_RECALL_DECISION, resolution.productRecallDecision(),
                MainWorkflowStateKeys.RESOLVED_PRODUCTS, resolution.resolvedProducts());
    }

    /** 仅在 SSE 运行中创建产品线索解析模型的 Token 发布上下文。 */
    private AgentTokenStreamContext streamContext(OverAllState state, MainWorkflowRequest request) {
        boolean enabled = state.value(MainWorkflowStateKeys.TOKEN_STREAMING_ENABLED, Boolean.class).orElse(false);
        if (!enabled) {
            return null;
        }
        String workflowInstanceId = state.value(MainWorkflowStateKeys.WORKFLOW_INSTANCE_ID, String.class)
                .orElseThrow(() -> new IllegalStateException("Missing workflow instance id in graph state"));
        return new AgentTokenStreamContext(
                workflowInstanceId,
                request.conversationId(),
                null,
                "product-reference-resolution-model",
                AgentTokenStreamContext.PHASE_PRODUCT_REFERENCE_RESOLUTION);
    }
}
