package com.xxx.insurance.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
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
        // 主工作流链路 7：结合已解析或人工确认的标准产品与会话历史，完成话题对齐和问题改写。
        AlignedWorkflowContext context = contextAlignmentService.align(request, effectiveResolution);
        return Map.of(MainWorkflowStateKeys.ALIGNED_CONTEXT, context);
    }

    @SuppressWarnings("unchecked")
    private List<ConfirmedProduct> resolvedProducts(OverAllState state) {
        return state.value(MainWorkflowStateKeys.RESOLVED_PRODUCTS, List.class)
                .map(value -> (List<ConfirmedProduct>) value)
                .orElse(List.of());
    }
}
