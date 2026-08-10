package com.xxx.insurance.ai.workflow.service;

import com.xxx.insurance.ai.agent.ChatModelStreamingExecutor;
import com.xxx.insurance.ai.memory.model.ChatMemoryMessageView;
import com.xxx.insurance.ai.memory.model.ConversationMemorySnapshot;
import com.xxx.insurance.ai.memory.service.AgentMemoryQueryService;
import com.xxx.insurance.ai.workflow.model.AlignedWorkflowContext;
import com.xxx.insurance.ai.workflow.model.ConversationTopicRelation;
import com.xxx.insurance.ai.workflow.model.MainWorkflowRequest;
import com.xxx.insurance.ai.workflow.model.ProductRecallDecision;
import com.xxx.insurance.ai.workflow.model.ProductRecallTrigger;
import com.xxx.insurance.ai.workflow.model.ProductReferenceResolution;
import com.xxx.insurance.product.model.ConfirmedProduct;
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

class ContextAlignmentServiceTests {

    @Test
    void rewritesCurrentQuestionWithConversationMemory() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(SystemMessage.class), any(UserMessage.class))).thenReturn("""
                {
                  "topicRelation": "CONTINUE",
                  "rewrittenQuestion": "请分析鑫享人生的收益情况",
                  "confirmedInformation": {
                    "products": ["鑫享人生"]
                  },
                  "entities": [
                    {"type": "PRODUCT", "value": "鑫享人生", "source": "MEMORY"}
                  ]
                }
                """);
        AgentMemoryQueryService memoryQueryService = (conversationId, limit) -> new ConversationMemorySnapshot(
                true,
                conversationId,
                null,
                List.of(new ChatMemoryMessageView(
                        "msg-1",
                        conversationId,
                        0,
                        "USER",
                        "帮我分析鑫享人生",
                        null,
                        Instant.parse("2026-08-05T00:00:00Z"))),
                List.of(),
                List.of(),
                List.of());
        ContextAlignmentService service = new ContextAlignmentService(
                chatModel, memoryQueryService, mock(ChatModelStreamingExecutor.class));

        MainWorkflowRequest request = new MainWorkflowRequest("它收益怎么样？", "conversation-001");
        ConfirmedProduct confirmedProduct = confirmedProduct("conversation-001", "PA-001", "鑫享人生");
        AlignedWorkflowContext result = service.align(request, new ProductReferenceResolution(
                request.conversationId(),
                request.message(),
                List.of(confirmedProduct),
                List.of("它"),
                new ProductRecallDecision(false, ProductRecallTrigger.CONFIRMED_PRODUCT,
                        "当前输入唯一映射到会话已确认产品"),
                List.of(confirmedProduct)));

        assertThat(result.originalQuestion()).isEqualTo("它收益怎么样？");
        assertThat(result.rewrittenQuestion()).isEqualTo("请分析鑫享人生的收益情况");
        assertThat(result.topicRelation()).isEqualTo(ConversationTopicRelation.CONTINUE);
        assertThat(result.confirmedInformation()).containsEntry("products", List.of("PA-001 鑫享人生"));
        assertThat(result.productRecallDecision().required()).isFalse();
        assertThat(result.chatMessageCount()).isEqualTo(1);
        assertThat(result.entities()).singleElement().satisfies(entity -> {
            assertThat(entity.type()).isEqualTo("PRODUCT");
            assertThat(entity.value()).isEqualTo("鑫享人生");
            assertThat(entity.source()).isEqualTo("MEMORY");
        });
    }

    @Test
    void requestsRecallForFirstExplicitProductWithoutHistory() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(SystemMessage.class), any(UserMessage.class))).thenReturn("""
                {
                  "topicRelation": "NO_HISTORY",
                  "rewrittenQuestion": "盛世典藏产品特点分析",
                  "confirmedInformation": {},
                  "entities": [
                    {"type": "PRODUCT", "value": "盛世典藏", "source": "CURRENT_QUERY"}
                  ]
                }
                """);
        AgentMemoryQueryService memoryQueryService = (conversationId, limit) ->
                new ConversationMemorySnapshot(false, conversationId, null,
                        List.of(), List.of(), List.of(), List.of());
        ContextAlignmentService service = new ContextAlignmentService(
                chatModel, memoryQueryService, mock(ChatModelStreamingExecutor.class));

        MainWorkflowRequest request = new MainWorkflowRequest("盛世典藏怎么样？", "conversation-002");
        AlignedWorkflowContext result = service.align(request, new ProductReferenceResolution(
                request.conversationId(),
                request.message(),
                List.of(),
                List.of("盛世典藏"),
                new ProductRecallDecision(true, ProductRecallTrigger.FIRST_EXPLICIT_PRODUCT,
                        "当前问题首次明确提及具体产品"),
                List.of()));

        assertThat(result.topicRelation()).isEqualTo(ConversationTopicRelation.NO_HISTORY);
        assertThat(result.productRecallDecision().required()).isTrue();
        assertThat(result.productRecallDecision().triggerType())
                .isEqualTo(ProductRecallTrigger.FIRST_EXPLICIT_PRODUCT);
    }

    @Test
    void skipsCandidateRecallForConditionOnlyProductSearch() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(SystemMessage.class), any(UserMessage.class))).thenReturn("""
                {
                  "topicRelation": "NO_HISTORY",
                  "rewrittenQuestion": "35岁男性 年交50万元 分红险筛选",
                  "confirmedInformation": {},
                  "entities": [
                    {"type": "PRODUCT", "value": "分红险", "source": "CURRENT_QUERY"},
                    {"type": "OTHER", "value": "35岁男性", "source": "CURRENT_QUERY"},
                    {"type": "OTHER", "value": "年交50万元", "source": "CURRENT_QUERY"}
                  ]
                }
                """);
        AgentMemoryQueryService memoryQueryService = (conversationId, limit) ->
                new ConversationMemorySnapshot(false, conversationId, null,
                        List.of(), List.of(), List.of(), List.of());
        ContextAlignmentService service = new ContextAlignmentService(
                chatModel, memoryQueryService, mock(ChatModelStreamingExecutor.class));

        MainWorkflowRequest request = new MainWorkflowRequest(
                "35岁男性，年交50万，找收益高的分红险", "conversation-003");
        AlignedWorkflowContext result = service.align(request, new ProductReferenceResolution(
                request.conversationId(),
                request.message(),
                List.of(),
                List.of(),
                new ProductRecallDecision(false, ProductRecallTrigger.CONDITION_FILTER,
                        "当前输入仅包含筛选条件"),
                List.of()));

        assertThat(result.productRecallDecision().required()).isFalse();
        assertThat(result.productRecallDecision().triggerType()).isEqualTo(ProductRecallTrigger.CONDITION_FILTER);
    }

    private ConfirmedProduct confirmedProduct(String conversationId, String productCode, String productName) {
        return new ConfirmedProduct(
                conversationId,
                productCode,
                productName,
                "年金保险",
                "示例保险公司",
                productName,
                "recall-001",
                "workflow-001",
                Instant.parse("2026-08-07T00:00:00Z"));
    }
}
