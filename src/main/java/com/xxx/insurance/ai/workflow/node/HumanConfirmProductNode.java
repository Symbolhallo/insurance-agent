package com.xxx.insurance.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.xxx.insurance.ai.workflow.model.MainWorkflowStateKeys;
import com.xxx.insurance.product.model.ConfirmedProduct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 产品人工确认恢复节点。
 *
 * <p>首次执行会在该节点之前由 Graph Runtime 中断。确认 API 将标准产品写回 Checkpoint
 * 后恢复执行，此节点只校验确认结果存在，不在节点内部阻塞等待用户输入。</p>
 */
@Component
public class HumanConfirmProductNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(HumanConfirmProductNode.class);

    @Override
    public Map<String, Object> apply(OverAllState state) {
        // 主工作流链路 10：恢复后校验标准产品已写入 State；该节点本身不阻塞等待用户。
        List<ConfirmedProduct> resolvedProducts = resolvedProducts(state);
        if (resolvedProducts.isEmpty()) {
            throw new IllegalStateException("Human confirmation resumed without resolved products");
        }
        log.info("[Workflow] node=human-confirm-product action=resume confirmedProductCount={}",
                resolvedProducts.size());
        return Map.of(MainWorkflowStateKeys.HUMAN_CONFIRM_REQUIRED, false);
    }

    @SuppressWarnings("unchecked")
    private List<ConfirmedProduct> resolvedProducts(OverAllState state) {
        return state.value(MainWorkflowStateKeys.RESOLVED_PRODUCTS, List.class)
                .map(value -> (List<ConfirmedProduct>) value)
                .orElse(List.of());
    }
}
