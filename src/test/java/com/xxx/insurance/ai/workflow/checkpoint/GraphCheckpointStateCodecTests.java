package com.xxx.insurance.ai.workflow.checkpoint;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.serializer.StateSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.workflow.checkpoint.config.GraphCheckpointConfig;
import com.xxx.insurance.ai.workflow.model.AlignedWorkflowContext;
import com.xxx.insurance.ai.workflow.model.AgentTaskExecutionResult;
import com.xxx.insurance.ai.workflow.model.AgentTaskStatus;
import com.xxx.insurance.ai.workflow.model.ConversationTopicRelation;
import com.xxx.insurance.ai.workflow.model.DagExecutionResult;
import com.xxx.insurance.ai.workflow.model.MainWorkflowRequest;
import com.xxx.insurance.ai.workflow.model.IntentRoute;
import com.xxx.insurance.ai.workflow.model.IntentRoutingResult;
import com.xxx.insurance.ai.workflow.model.OutputReviewDecision;
import com.xxx.insurance.ai.workflow.model.OutputReviewResult;
import com.xxx.insurance.ai.workflow.model.WorkflowSummaryResult;
import com.xxx.insurance.ai.workflow.model.WorkflowEntity;
import com.xxx.insurance.ai.workflow.model.WorkflowPlan;
import com.xxx.insurance.ai.workflow.model.WorkflowPlanTask;
import com.xxx.insurance.ai.workflow.model.ProductRecallDecision;
import com.xxx.insurance.ai.workflow.model.ProductRecallTrigger;
import com.xxx.insurance.ai.workflow.model.ProductReferenceResolution;
import com.xxx.insurance.ai.workflow.model.SubAgentExecutionResult;
import com.xxx.insurance.product.model.ProductCandidate;
import com.xxx.insurance.product.model.ConfirmedProduct;
import com.xxx.insurance.product.model.ProductRecallResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GraphCheckpointStateCodecTests {

    private final StateSerializer serializer = new GraphCheckpointConfig()
            .mainWorkflowStateSerializer(new ObjectMapper());

    private final GraphCheckpointStateCodec codec = new GraphCheckpointStateCodec(serializer);

    @Test
    void roundTripsSpringAiMessagesAndWorkflowRecords() {
        Map<String, Object> state = Map.of(
                "request", new MainWorkflowRequest("分析鑫享人生", "conversation-001"),
                "messages", List.of(UserMessage.builder().text("分析鑫享人生").build()),
                "iteration", 3);

        GraphCheckpointStateCodec.EncodedState encoded = codec.encode(state);
        Map<String, Object> decoded = codec.decode(encoded.payload(), encoded.contentType());

        assertThat(decoded.get("request")).isEqualTo(state.get("request"));
        assertThat((List<?>) decoded.get("messages")).singleElement().isInstanceOf(UserMessage.class);
        assertThat(decoded.get("iteration")).isEqualTo(3);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void normalizesIntentRoutesThatGraphRuntimeMaterializedAsMaps() {
        List degradedRoutes = List.of(Map.of(
                "intent", "POLICY_QUERY",
                "targetAgent", "policy-query-agent",
                "intentionQuery", "查询有效保单",
                "reason", "客户保单查询"));
        IntentRoutingResult degradedResult = new IntentRoutingResult(
                "MULTI_INTENT", null, "混合意图", degradedRoutes);

        GraphCheckpointStateCodec.EncodedState encoded = codec.encode(
                Map.of("intentRoutingResult", degradedResult));
        Map<String, Object> decoded = codec.decode(encoded.payload(), encoded.contentType());
        IntentRoutingResult restored = (IntentRoutingResult) decoded.get("intentRoutingResult");

        assertThat(restored.routes()).singleElement().isInstanceOf(IntentRoute.class);
        assertThat(restored.routes().getFirst().targetAgent()).isEqualTo("policy-query-agent");
        codec.encode(decoded);
    }

    @Test
    void preservesNestedWorkflowRecordTypesAcrossConsecutiveCheckpoints() {
        ConfirmedProduct confirmedProduct = new ConfirmedProduct(
                "conversation-001",
                "PA-001",
                "鑫享人生",
                "年金保险",
                "示例保险公司",
                "鑫享人生",
                "prc-001",
                "workflow-001",
                Instant.parse("2026-08-07T00:00:00Z"));
        AlignedWorkflowContext alignedContext = new AlignedWorkflowContext(
                "conversation-001",
                "它有哪些风险？",
                ConversationTopicRelation.CONTINUE,
                "保险产品 PA-001 有哪些风险？",
                Map.of("products", List.of("PA-001")),
                List.of(new WorkflowEntity("PRODUCT", "PA-001", "MEMORY")),
                new ProductRecallDecision(
                        false,
                        ProductRecallTrigger.CONFIRMED_PRODUCT,
                        "历史中已经确认 PA-001"),
                List.of(confirmedProduct),
                true,
                2,
                4,
                1,
                "trace-001",
                Instant.parse("2026-08-07T00:00:00Z"));
        WorkflowPlan workflowPlan = new WorkflowPlan(
                "分析 PA-001 风险",
                List.of(new WorkflowPlanTask(
                        "task-1",
                        1,
                        "product-analysis-agent",
                        "分析 PA-001 风险",
                        List.of())),
                "根据产品分析意图生成单任务计划");
        ProductRecallResult recallResult = new ProductRecallResult(
                "prc-001",
                "养老保险",
                1,
                List.of(new ProductCandidate(
                        "PA-003",
                        "稳盈养老年金保险",
                        "养老年金保险",
                        "示例养老保险股份有限公司",
                        new BigDecimal("0.98"),
                        "查询命中养老年金需求")),
                true,
                1,
                Instant.parse("2026-08-07T00:00:01Z"));
        ProductReferenceResolution productResolution = new ProductReferenceResolution(
                "conversation-001",
                "它有哪些风险？",
                List.of(confirmedProduct),
                List.of("它"),
                alignedContext.productRecallDecision(),
                List.of(confirmedProduct));
        SubAgentExecutionResult agentResult = new SubAgentExecutionResult(
                "knowledge-qa-agent",
                "conversation-001",
                "kqa-001",
                "犹豫期是重新审视投保决定的期间。",
                true,
                100,
                Instant.parse("2026-08-07T00:00:02Z"),
                18,
                true,
                2);
        DagExecutionResult dagResult = DagExecutionResult.from(List.of(new AgentTaskExecutionResult(
                "task-1",
                1,
                "knowledge-qa-agent",
                AgentTaskStatus.SUCCESS,
                agentResult,
                null,
                null,
                Instant.parse("2026-08-07T00:00:01Z"),
                Instant.parse("2026-08-07T00:00:02Z"),
                100)));
        OutputReviewResult reviewResult = new OutputReviewResult(
                "orr-001",
                OutputReviewDecision.REWRITE,
                "审核后的安全回答",
                List.of("removed risky wording"),
                false,
                10,
                Instant.parse("2026-08-07T00:00:03Z"));
        WorkflowSummaryResult summaryResult = new WorkflowSummaryResult(
                "wfs-001",
                false,
                1,
                1,
                "犹豫期是重新审视投保决定的期间。",
                1,
                Instant.parse("2026-08-07T00:00:02Z"));
        IntentRoutingResult routingResult = new IntentRoutingResult(
                "MULTI_INTENT",
                null,
                "问题包含两个业务目标",
                List.of(
                        new IntentRoute("POLICY_QUERY", "policy-query-agent", "查询有效保单", "保单查询"),
                        new IntentRoute("ASSET_QUERY", "asset-query-agent", "查询资产余额", "资产查询")));
        Map<String, Object> state = Map.of(
                "alignedContext", alignedContext,
                "workflowPlan", workflowPlan,
                "productRecallResult", recallResult,
                "productReferenceResolution", productResolution,
                "resolvedProducts", List.of(confirmedProduct),
                "intentRoutingResult", routingResult,
                "dagExecutionResult", dagResult,
                "summaryResult", summaryResult,
                "outputReviewResult", reviewResult);

        GraphCheckpointStateCodec.EncodedState firstEncoded = codec.encode(state);
        Map<String, Object> firstDecoded = codec.decode(firstEncoded.payload(), firstEncoded.contentType());

        AlignedWorkflowContext decodedContext = (AlignedWorkflowContext) firstDecoded.get("alignedContext");
        WorkflowPlan decodedPlan = (WorkflowPlan) firstDecoded.get("workflowPlan");
        ProductRecallResult decodedRecallResult = (ProductRecallResult) firstDecoded.get("productRecallResult");
        ProductReferenceResolution decodedResolution = (ProductReferenceResolution)
                firstDecoded.get("productReferenceResolution");
        DagExecutionResult decodedDagResult = (DagExecutionResult) firstDecoded.get("dagExecutionResult");
        WorkflowSummaryResult decodedSummaryResult = (WorkflowSummaryResult) firstDecoded.get("summaryResult");
        OutputReviewResult decodedReviewResult = (OutputReviewResult) firstDecoded.get("outputReviewResult");
        IntentRoutingResult decodedRoutingResult = (IntentRoutingResult) firstDecoded.get("intentRoutingResult");
        assertThat(decodedContext.entities()).singleElement().isInstanceOf(WorkflowEntity.class);
        assertThat(decodedContext.productRecallDecision().triggerType())
                .isEqualTo(ProductRecallTrigger.CONFIRMED_PRODUCT);
        assertThat(decodedContext.resolvedProducts()).singleElement().isInstanceOf(ConfirmedProduct.class);
        assertThat(decodedPlan.tasks()).singleElement().isInstanceOf(WorkflowPlanTask.class);
        assertThat(decodedRecallResult.candidates()).singleElement().isInstanceOf(ProductCandidate.class);
        assertThat(decodedResolution.resolvedProducts()).singleElement().isInstanceOf(ConfirmedProduct.class);
        assertThat((List<?>) firstDecoded.get("resolvedProducts"))
                .singleElement().isInstanceOf(ConfirmedProduct.class);
        assertThat(decodedDagResult.taskResults()).singleElement().satisfies(task -> {
            assertThat(task).isInstanceOf(AgentTaskExecutionResult.class);
            assertThat(task.response().agentName()).isEqualTo("knowledge-qa-agent");
        });
        assertThat(decodedReviewResult.decision()).isEqualTo(OutputReviewDecision.REWRITE);
        assertThat(decodedReviewResult.publishableAnswer()).isEqualTo("审核后的安全回答");
        assertThat(decodedSummaryResult.summaryId()).isEqualTo("wfs-001");
        assertThat(decodedSummaryResult.modelInvoked()).isFalse();
        assertThat(decodedRoutingResult.routes()).hasSize(2).allMatch(IntentRoute.class::isInstance);

        // Graph 会在每个节点后再次保存完整 State，第二次序列化必须同样成功。
        GraphCheckpointStateCodec.EncodedState secondEncoded = codec.encode(firstDecoded);
        Map<String, Object> secondDecoded = codec.decode(secondEncoded.payload(), secondEncoded.contentType());
        AlignedWorkflowContext secondContext = (AlignedWorkflowContext) secondDecoded.get("alignedContext");
        WorkflowPlan secondPlan = (WorkflowPlan) secondDecoded.get("workflowPlan");
        ProductRecallResult secondRecallResult = (ProductRecallResult) secondDecoded.get("productRecallResult");
        ProductReferenceResolution secondResolution = (ProductReferenceResolution)
                secondDecoded.get("productReferenceResolution");
        DagExecutionResult secondDagResult = (DagExecutionResult) secondDecoded.get("dagExecutionResult");
        WorkflowSummaryResult secondSummaryResult = (WorkflowSummaryResult) secondDecoded.get("summaryResult");
        OutputReviewResult secondReviewResult = (OutputReviewResult) secondDecoded.get("outputReviewResult");
        IntentRoutingResult secondRoutingResult = (IntentRoutingResult) secondDecoded.get("intentRoutingResult");
        assertThat(secondContext.entities()).singleElement().isInstanceOf(WorkflowEntity.class);
        assertThat(secondContext.resolvedProducts()).singleElement().isInstanceOf(ConfirmedProduct.class);
        assertThat(secondPlan.tasks()).singleElement().isInstanceOf(WorkflowPlanTask.class);
        assertThat(secondRecallResult.candidates()).singleElement().isInstanceOf(ProductCandidate.class);
        assertThat(secondResolution.resolvedProducts()).singleElement().isInstanceOf(ConfirmedProduct.class);
        assertThat(secondDagResult.taskResults().getFirst().response().invocationId()).isEqualTo("kqa-001");
        assertThat(secondSummaryResult.answer()).contains("犹豫期");
        assertThat(secondReviewResult.reviewRequestId()).isEqualTo("orr-001");
        assertThat(secondRoutingResult.routes().getFirst().intentionQuery()).isEqualTo("查询有效保单");
    }
}
