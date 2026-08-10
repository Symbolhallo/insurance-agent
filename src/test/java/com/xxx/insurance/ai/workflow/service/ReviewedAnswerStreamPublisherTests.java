package com.xxx.insurance.ai.workflow.service;

import com.xxx.insurance.ai.workflow.model.OutputReviewDecision;
import com.xxx.insurance.ai.workflow.model.OutputReviewResult;
import com.xxx.insurance.ai.workflow.model.WorkflowSseEventType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ReviewedAnswerStreamPublisherTests {

    @Test
    void publishesOnlyReviewedAnswerInBoundedChunks() {
        WorkflowEventPublisher eventPublisher = mock(WorkflowEventPublisher.class);
        ReviewedAnswerStreamPublisher publisher = new ReviewedAnswerStreamPublisher(eventPublisher);
        String reviewedAnswer = "保".repeat(600);

        publisher.publish("wfi-001", "conversation-001", true, result(
                OutputReviewDecision.REWRITE, reviewedAnswer));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventPublisher, times(2)).publish(
                eq("wfi-001"), eq("conversation-001"), eq(WorkflowSseEventType.AGENT_STREAM),
                eq("output-review"), dataCaptor.capture());
        assertThat(dataCaptor.getAllValues())
                .extracting(data -> (String) data.get("content"))
                .allMatch(chunk -> chunk.codePointCount(0, chunk.length()) <= 512);
        assertThat(dataCaptor.getAllValues().getLast().get("last")).isEqualTo(true);
        assertThat(dataCaptor.getAllValues().getFirst().get("deliveryMode"))
                .isEqualTo("BUFFERED_UNTIL_REVIEW");
    }

    @Test
    void doesNotPublishBlockedAnswer() {
        WorkflowEventPublisher eventPublisher = mock(WorkflowEventPublisher.class);
        ReviewedAnswerStreamPublisher publisher = new ReviewedAnswerStreamPublisher(eventPublisher);

        publisher.publish("wfi-001", "conversation-001", true,
                result(OutputReviewDecision.BLOCK, "禁止发布"));

        verify(eventPublisher, never()).publish(any(), any(), any(), any(), any());
    }

    private OutputReviewResult result(OutputReviewDecision decision, String answer) {
        return new OutputReviewResult(
                "orr-001", decision, answer, List.of(), false, 1,
                Instant.parse("2026-08-10T00:00:00Z"));
    }
}
