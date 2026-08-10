package com.xxx.insurance.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.xxx.insurance.ai.workflow.model.MainWorkflowStateKeys;
import com.xxx.insurance.product.model.ConfirmedProduct;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HumanConfirmProductNodeTests {

    private final HumanConfirmProductNode node = new HumanConfirmProductNode();

    @Test
    void continuesAfterResolvedProductsAreWrittenByConfirmationApi() throws Exception {
        ConfirmedProduct product = new ConfirmedProduct(
                "conversation-001",
                "PA-001",
                "鑫享人生",
                "年金保险",
                "示例保险公司",
                "鑫享人生",
                "recall-001",
                "workflow-001",
                Instant.parse("2026-08-07T00:00:00Z"));
        OverAllState state = new OverAllState(Map.of(
                MainWorkflowStateKeys.RESOLVED_PRODUCTS, List.of(product)));

        Map<String, Object> result = node.apply(state);

        assertThat(result).containsEntry(MainWorkflowStateKeys.HUMAN_CONFIRM_REQUIRED, false);
    }

    @Test
    void rejectsResumeWithoutConfirmedProduct() {
        OverAllState state = new OverAllState(Map.of());

        assertThatThrownBy(() -> node.apply(state))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without resolved products");
    }
}
