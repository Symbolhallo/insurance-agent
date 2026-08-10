package com.xxx.insurance.ai.workflow.client;

import com.xxx.insurance.ai.workflow.model.OutputReviewDecision;
import com.xxx.insurance.ai.workflow.model.OutputReviewRequest;
import com.xxx.insurance.ai.workflow.model.OutputReviewResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockOutputReviewGatewayTests {

    private final MockOutputReviewGateway gateway = new MockOutputReviewGateway();

    @Test
    void passesNonBlankCandidateAnswer() {
        OutputReviewResult result = gateway.review(request("候选回答"));

        assertThat(result.decision()).isEqualTo(OutputReviewDecision.PASS);
        assertThat(result.publishableAnswer()).isEqualTo("候选回答");
        assertThat(result.mockData()).isTrue();
    }

    @Test
    void blocksBlankCandidateAnswerWithSafeFallback() {
        OutputReviewResult result = gateway.review(request(" "));

        assertThat(result.decision()).isEqualTo(OutputReviewDecision.BLOCK);
        assertThat(result.publishableAnswer()).contains("未通过输出审核");
    }

    private OutputReviewRequest request(String candidateAnswer) {
        return new OutputReviewRequest(
                "orr-001",
                "workflow-001",
                "conversation-001",
                "原始问题",
                "改写问题",
                candidateAnswer,
                List.of());
    }
}
