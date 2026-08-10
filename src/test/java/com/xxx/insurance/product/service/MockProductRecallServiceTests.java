package com.xxx.insurance.product.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.retrieval.model.RetrievalCallRecord;
import com.xxx.insurance.ai.retrieval.service.RetrievalCallRecorder;
import com.xxx.insurance.product.model.ProductRecallExecutionContext;
import com.xxx.insurance.product.model.ProductRecallRequest;
import com.xxx.insurance.product.model.ProductRecallResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MockProductRecallServiceTests {

    @Test
    void prioritizesCriticalIllnessProductAndRecordsWorkflowContext() {
        CapturingRecorder recorder = new CapturingRecorder();
        MockProductRecallService service = new MockProductRecallService(
                new MockProductCatalog(), recorder, new ObjectMapper().findAndRegisterModules());

        ProductRecallResult result = service.recall(
                new ProductRecallRequest("我想找一款重大疾病保险", "conversation-001", 2, Map.of()),
                new ProductRecallExecutionContext("conversation-001", "workflow-001"));

        assertThat(result.mockData()).isTrue();
        assertThat(result.candidates()).hasSize(2);
        assertThat(result.candidates().getFirst().productCode()).isEqualTo("PA-002");
        assertThat(recorder.records).singleElement().satisfies(record -> {
            assertThat(record.workflowInstanceId()).isEqualTo("workflow-001");
            assertThat(record.conversationId()).isEqualTo("conversation-001");
            assertThat(record.status()).isEqualTo("SUCCESS");
            assertThat(record.resultJson()).contains("PA-002");
        });
    }

    @Test
    void usesDefaultTopKForGenericQuery() {
        MockProductRecallService service = new MockProductRecallService(
                new MockProductCatalog(), record -> { }, new ObjectMapper().findAndRegisterModules());

        ProductRecallResult result = service.recall(
                new ProductRecallRequest("给我推荐保险产品", "conversation-002", null, null),
                new ProductRecallExecutionContext("conversation-002", null));

        assertThat(result.topK()).isEqualTo(3);
        assertThat(result.candidates()).extracting(candidate -> candidate.productCode())
                .containsExactly("PA-001", "PA-002", "PA-003");
    }

    private static final class CapturingRecorder implements RetrievalCallRecorder {

        private final List<RetrievalCallRecord> records = new ArrayList<>();

        @Override
        public void record(RetrievalCallRecord record) {
            records.add(record);
        }
    }
}
