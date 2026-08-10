package com.xxx.insurance.ai.workflow.service;

import com.xxx.insurance.ai.workflow.model.MainWorkflowRequest;
import com.xxx.insurance.ai.workflow.model.ProductRecallTrigger;
import com.xxx.insurance.ai.workflow.model.ProductReferenceResolution;
import com.xxx.insurance.product.model.ConfirmedProduct;
import com.xxx.insurance.product.service.ConversationConfirmedProductService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductReferenceResolutionServiceTests {

    @Test
    void recallsCandidatesWhenProductIsFirstMentioned() {
        ProductReferenceResolution result = resolve(
                List.of(),
                """
                        {
                          "detectedProductClues": ["盛世典藏"],
                          "matchedConfirmedProductCodes": [],
                          "productRecallDecision": {
                            "required": true,
                            "triggerType": "FIRST_EXPLICIT_PRODUCT",
                            "reason": "当前会话首次提及具体产品"
                          }
                        }
                        """,
                "盛世典藏怎么样？");

        assertThat(result.productRecallDecision().required()).isTrue();
        assertThat(result.productRecallDecision().triggerType())
                .isEqualTo(ProductRecallTrigger.FIRST_EXPLICIT_PRODUCT);
        assertThat(result.resolvedProducts()).isEmpty();
    }

    @Test
    void resolvesFollowUpAgainstConfirmedProductInSameConversation() {
        ConfirmedProduct confirmedProduct = confirmedProduct();
        ProductReferenceResolution result = resolve(
                List.of(confirmedProduct),
                """
                        {
                          "detectedProductClues": ["它"],
                          "matchedConfirmedProductCodes": ["PA-001"],
                          "productRecallDecision": {
                            "required": false,
                            "triggerType": "CONFIRMED_PRODUCT",
                            "reason": "指代唯一映射到当前会话已确认产品"
                          }
                        }
                        """,
                "它持有20年能拿多少钱？");

        assertThat(result.productRecallDecision().required()).isFalse();
        assertThat(result.resolvedProducts()).containsExactly(confirmedProduct);
    }

    @Test
    void skipsEntityRecallForConditionOnlyFilter() {
        ProductReferenceResolution result = resolve(
                List.of(),
                """
                        {
                          "detectedProductClues": [],
                          "matchedConfirmedProductCodes": [],
                          "productRecallDecision": {
                            "required": false,
                            "triggerType": "CONDITION_FILTER",
                            "reason": "只有客户画像和产品筛选条件"
                          }
                        }
                        """,
                "35岁男性，年交50万，筛选分红险");

        assertThat(result.productRecallDecision().required()).isFalse();
        assertThat(result.productRecallDecision().triggerType())
                .isEqualTo(ProductRecallTrigger.CONDITION_FILTER);
    }

    private ProductReferenceResolution resolve(List<ConfirmedProduct> confirmedProducts,
                                               String modelOutput,
                                               String message) {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(SystemMessage.class), any(UserMessage.class))).thenReturn(modelOutput);
        ConversationConfirmedProductService confirmedProductService = mock(
                ConversationConfirmedProductService.class);
        when(confirmedProductService.findConfirmedProducts("conversation-001"))
                .thenReturn(confirmedProducts);
        return new ProductReferenceResolutionService(chatModel, confirmedProductService)
                .resolve(new MainWorkflowRequest(message, "conversation-001"));
    }

    private ConfirmedProduct confirmedProduct() {
        return new ConfirmedProduct(
                "conversation-001",
                "PA-001",
                "鑫享人生",
                "年金保险",
                "示例保险公司",
                "鑫享人生",
                "recall-001",
                "workflow-001",
                Instant.parse("2026-08-07T00:00:00Z"));
    }
}
