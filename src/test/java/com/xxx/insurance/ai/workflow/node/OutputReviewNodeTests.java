package com.xxx.insurance.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.xxx.insurance.ai.workflow.client.OutputReviewGateway;
import com.xxx.insurance.ai.workflow.model.AgentTaskExecutionResult;
import com.xxx.insurance.ai.workflow.model.AgentTaskStatus;
import com.xxx.insurance.ai.workflow.model.AlignedWorkflowContext;
import com.xxx.insurance.ai.workflow.model.ConversationTopicRelation;
import com.xxx.insurance.ai.workflow.model.DagExecutionResult;
import com.xxx.insurance.ai.workflow.model.MainWorkflowStateKeys;
import com.xxx.insurance.ai.workflow.model.OutputReviewDecision;
import com.xxx.insurance.ai.workflow.model.OutputReviewRequest;
import com.xxx.insurance.ai.workflow.model.OutputReviewResult;
import com.xxx.insurance.ai.workflow.model.ProductRecallDecision;
import com.xxx.insurance.ai.workflow.model.ProductRecallTrigger;
import com.xxx.insurance.ai.workflow.model.SubAgentExecutionResult;
import com.xxx.insurance.ai.workflow.model.WorkflowSummaryResult;
import com.xxx.insurance.ai.workflow.service.ReviewedAnswerStreamPublisher;
import com.xxx.insurance.knowledge.agent.KnowledgeQaAgent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutputReviewNodeTests {

    @Test
    void writesValidatedGatewayResultToGraphState() throws Exception {
        OutputReviewGateway gateway = mock(OutputReviewGateway.class);
        when(gateway.review(any(OutputReviewRequest.class))).thenAnswer(invocation -> {
            OutputReviewRequest request = invocation.getArgument(0);
            return new OutputReviewResult(
                    request.reviewRequestId(),
                    OutputReviewDecision.PASS,
                    request.candidateAnswer(),
                    List.of("passed"),
                    true,
                    1,
                    Instant.parse("2026-08-10T00:00:02Z"));
        });
        ReviewedAnswerStreamPublisher streamPublisher = mock(ReviewedAnswerStreamPublisher.class);
        OutputReviewNode node = new OutputReviewNode(gateway, streamPublisher);

        Map<String, Object> result = node.apply(state());

        assertThat(result.get(MainWorkflowStateKeys.OUTPUT_REVIEW_RESULT))
                .isInstanceOfSatisfying(OutputReviewResult.class, review -> {
                    assertThat(review.decision()).isEqualTo(OutputReviewDecision.PASS);
                    assertThat(review.publishableAnswer()).isEqualTo("知识问答结果");
                });
        assertThat(result.get(MainWorkflowStateKeys.FINAL_ANSWER)).isEqualTo("知识问答结果");
        verify(streamPublisher).publish(
                eq("workflow-001"), eq("conversation-001"), eq(true), any(OutputReviewResult.class));
    }

    @Test
    void rejectsMismatchedReviewRequestId() {
        OutputReviewGateway gateway = request -> new OutputReviewResult(
                "another-request",
                OutputReviewDecision.PASS,
                "不可发布",
                List.of(),
                true,
                1,
                Instant.parse("2026-08-10T00:00:02Z"));
        OutputReviewNode node = new OutputReviewNode(gateway, mock(ReviewedAnswerStreamPublisher.class));

        assertThatThrownBy(() -> node.apply(state()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid result");
    }

    private OverAllState state() {
        return new OverAllState(Map.of(
                MainWorkflowStateKeys.WORKFLOW_INSTANCE_ID, "workflow-001",
                MainWorkflowStateKeys.TOKEN_STREAMING_ENABLED, true,
                MainWorkflowStateKeys.ALIGNED_CONTEXT, alignedContext(),
                MainWorkflowStateKeys.SUMMARY_RESULT, new WorkflowSummaryResult(
                        "wfs-001", false, 1, 1, "知识问答结果", 1,
                        Instant.parse("2026-08-10T00:00:01Z")),
                MainWorkflowStateKeys.DAG_EXECUTION_RESULT, dagResult()));
    }

    private AlignedWorkflowContext alignedContext() {
        return new AlignedWorkflowContext(
                "conversation-001",
                "犹豫期是什么？",
                ConversationTopicRelation.NO_HISTORY,
                "保险合同的犹豫期是什么？",
                Map.of(),
                List.of(),
                new ProductRecallDecision(false, ProductRecallTrigger.NON_PRODUCT_TOPIC, "非产品话题"),
                List.of(),
                false,
                0,
                0,
                0,
                "trace-001",
                Instant.parse("2026-08-10T00:00:00Z"));
    }

    private DagExecutionResult dagResult() {
        SubAgentExecutionResult response = new SubAgentExecutionResult(
                KnowledgeQaAgent.AGENT_NAME,
                "conversation-001",
                "kqa-001",
                "知识问答结果",
                true,
                100,
                Instant.parse("2026-08-10T00:00:01Z"),
                6,
                false,
                0);
        return DagExecutionResult.from(List.of(new AgentTaskExecutionResult(
                "task-1",
                1,
                KnowledgeQaAgent.AGENT_NAME,
                AgentTaskStatus.SUCCESS,
                response,
                null,
                null,
                Instant.parse("2026-08-10T00:00:00Z"),
                Instant.parse("2026-08-10T00:00:01Z"),
                100)));
    }
}
