package com.xxx.insurance.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.xxx.insurance.ai.workflow.model.MainWorkflowRequest;
import com.xxx.insurance.ai.workflow.model.MainWorkflowStateKeys;
import com.xxx.insurance.product.model.ProductRecallExecutionContext;
import com.xxx.insurance.product.model.ProductRecallRequest;
import com.xxx.insurance.product.model.ProductRecallResult;
import com.xxx.insurance.product.service.ProductRecallService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 调用产品召回边界并把候选写入 Graph State。
 */
@Component
public class ProductCandidateRetrievalNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(ProductCandidateRetrievalNode.class);

    private final ProductRecallService productRecallService;

    public ProductCandidateRetrievalNode(ProductRecallService productRecallService) {
        this.productRecallService = productRecallService;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        MainWorkflowRequest workflowRequest = state
                .value(MainWorkflowStateKeys.REQUEST, MainWorkflowRequest.class)
                .orElseThrow(() -> new IllegalStateException("Missing main workflow request in graph state"));
        String workflowInstanceId = state
                .value(MainWorkflowStateKeys.WORKFLOW_INSTANCE_ID, String.class)
                .orElse(null);
        ProductRecallRequest request = new ProductRecallRequest(
                workflowRequest.message(),
                workflowRequest.conversationId(),
                3,
                Map.of("domain", "insurance-product"));
        // 主工作流链路 5：召回待确认的标准产品候选，并写入召回审计记录和 Checkpoint State。
        ProductRecallResult result = productRecallService.recall(
                request,
                new ProductRecallExecutionContext(workflowRequest.conversationId(), workflowInstanceId));
        log.info("[Workflow] node=retrieve-product-candidates action=recall conversationId={} "
                        + "retrievalCallId={} candidateCount={}",
                workflowRequest.conversationId(), result.retrievalCallId(), result.candidates().size());
        return Map.of(
                MainWorkflowStateKeys.PRODUCT_RECALL_RESULT, result,
                MainWorkflowStateKeys.HUMAN_CONFIRM_REQUIRED, true);
    }
}
