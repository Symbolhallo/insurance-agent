package com.xxx.insurance.ai.workflow.service;

import com.xxx.insurance.ai.agent.AgentTokenStreamContext;
import com.xxx.insurance.ai.workflow.config.WorkflowSseProperties;
import com.xxx.insurance.ai.workflow.model.WorkflowSseEventType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowAgentTokenStreamSinkTests {

    @Test
    void publishesFirstChunkSynchronouslyForImmediateFrontendFeedback() {
        try (Fixture fixture = fixture(Duration.ofSeconds(1), 128)) {
            AgentTokenStreamContext context = subAgentContext("task-2", "knowledge-qa-agent");

            fixture.sink.publishToken(context, "stream-001", 1, "现金");

            assertThat(fixture.publisher.events()).singleElement().satisfies(event -> {
                assertThat(event.type()).isEqualTo(WorkflowSseEventType.AGENT_STREAM);
                assertThat(event.node()).isEqualTo("agent-invoke");
                assertThat(event.data())
                        .containsEntry("streamId", "stream-001")
                        .containsEntry("taskId", "task-2")
                        .containsEntry("agentName", "knowledge-qa-agent")
                        .containsEntry("phase", "SUB_AGENT")
                        .containsEntry("content", "现金")
                        .containsEntry("firstChunkIndex", 1L)
                        .containsEntry("chunkIndex", 1L)
                        .containsEntry("sourceChunkCount", 1)
                        .containsEntry("last", false)
                        .containsEntry("deliveryMode", "LIVE_MODEL_STREAM");
            });
            fixture.sink.complete(context, "stream-001", 1);
        }
    }

    @Test
    void mergesSubsequentChunksAtCharacterThresholdWithoutChangingText() {
        try (Fixture fixture = fixture(Duration.ofSeconds(1), 3)) {
            AgentTokenStreamContext context = subAgentContext("task-2", "knowledge-qa-agent");

            fixture.sink.publishToken(context, "stream-001", 1, "现");
            fixture.sink.publishToken(context, "stream-001", 2, "金");
            fixture.sink.publishToken(context, "stream-001", 3, "价");
            fixture.sink.publishToken(context, "stream-001", 4, "值");
            fixture.sink.complete(context, "stream-001", 4);

            assertThat(fixture.publisher.events()).hasSize(3);
            PublishedEvent merged = fixture.publisher.events().get(1);
            assertThat(merged.data())
                    .containsEntry("content", "金价值")
                    .containsEntry("firstChunkIndex", 2L)
                    .containsEntry("chunkIndex", 4L)
                    .containsEntry("sourceChunkCount", 3);
            assertThat(concatenatedText(fixture.publisher.events())).isEqualTo("现金价值");
            assertThat(fixture.publisher.events().get(2).data())
                    .containsEntry("content", "")
                    .containsEntry("last", true);
        }
    }

    @Test
    void flushesSmallBatchWithinConfiguredMaximumDelay() throws InterruptedException {
        try (Fixture fixture = fixture(Duration.ofMillis(30), 128)) {
            AgentTokenStreamContext context = preWorkflowContext();

            fixture.sink.publishToken(context, "stream-intent-001", 1, "{");
            fixture.sink.publishToken(context, "stream-intent-001", 2, "\"intentions\"");

            awaitEventCount(fixture.publisher, 2, Duration.ofSeconds(1));
            assertThat(fixture.publisher.events().get(1).data())
                    .containsEntry("content", "\"intentions\"")
                    .containsEntry("chunkIndex", 2L);
            fixture.sink.complete(context, "stream-intent-001", 2);
        }
    }

    @Test
    void completeFlushesPendingTextBeforeCompletionMarker() {
        try (Fixture fixture = fixture(Duration.ofSeconds(1), 128)) {
            AgentTokenStreamContext context = summaryContext();

            fixture.sink.publishToken(context, "stream-summary-001", 1, "第一段");
            fixture.sink.publishToken(context, "stream-summary-001", 2, " 第二段");
            fixture.sink.complete(context, "stream-summary-001", 2);

            assertThat(fixture.publisher.events()).hasSize(3);
            assertThat(concatenatedText(fixture.publisher.events())).isEqualTo("第一段 第二段");
            assertThat(fixture.publisher.events().get(1).data()).containsEntry("content", " 第二段");
            assertThat(fixture.publisher.events().get(2).data())
                    .doesNotContainKey("taskId")
                    .containsEntry("content", "")
                    .containsEntry("chunkIndex", 2L)
                    .containsEntry("last", true)
                    .containsEntry("phase", "SUMMARY");
        }
    }

    @Test
    void keepsParallelStreamsIndependent() {
        try (Fixture fixture = fixture(Duration.ofSeconds(1), 128)) {
            AgentTokenStreamContext first = subAgentContext("task-1", "product-analysis-agent");
            AgentTokenStreamContext second = subAgentContext("task-2", "knowledge-qa-agent");

            fixture.sink.publishToken(first, "stream-001", 1, "产品");
            fixture.sink.publishToken(second, "stream-002", 1, "知识");
            fixture.sink.publishToken(first, "stream-001", 2, "分析");
            fixture.sink.publishToken(second, "stream-002", 2, "问答");
            fixture.sink.complete(first, "stream-001", 2);
            fixture.sink.complete(second, "stream-002", 2);

            assertThat(textForStream(fixture.publisher.events(), "stream-001")).isEqualTo("产品分析");
            assertThat(textForStream(fixture.publisher.events(), "stream-002")).isEqualTo("知识问答");
        }
    }

    @Test
    void abortFlushesGeneratedTextWithoutNormalCompletionMarker() {
        try (Fixture fixture = fixture(Duration.ofSeconds(1), 128)) {
            AgentTokenStreamContext context = subAgentContext("task-1", "product-analysis-agent");

            fixture.sink.publishToken(context, "stream-001", 1, "已经生成");
            fixture.sink.publishToken(context, "stream-001", 2, "的正文");
            fixture.sink.abort(context, "stream-001");

            assertThat(concatenatedText(fixture.publisher.events())).isEqualTo("已经生成的正文");
            assertThat(fixture.publisher.events())
                    .allSatisfy(event -> assertThat(event.data()).containsEntry("last", false));
        }
    }

    @Test
    void coalescesOneThousandSmallChunksIntoTenPersistedEvents() {
        try (Fixture fixture = fixture(Duration.ofSeconds(1), 128)) {
            AgentTokenStreamContext context = subAgentContext("task-1", "product-analysis-agent");

            for (int index = 1; index <= 1_000; index++) {
                fixture.sink.publishToken(context, "stream-volume-001", index, "字");
            }
            fixture.sink.complete(context, "stream-volume-001", 1_000);

            assertThat(fixture.publisher.events()).hasSize(10);
            assertThat(concatenatedText(fixture.publisher.events())).hasSize(1_000);
        }
    }

    private Fixture fixture(Duration maxDelay, int maxCharacters) {
        RecordingPublisher publisher = new RecordingPublisher();
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        WorkflowSseProperties properties = new WorkflowSseProperties(
                Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMillis(500),
                maxDelay, maxCharacters);
        return new Fixture(publisher, scheduler,
                new WorkflowAgentTokenStreamSink(publisher, properties, scheduler));
    }

    private AgentTokenStreamContext subAgentContext(String taskId, String agentName) {
        return new AgentTokenStreamContext(
                "wfi-001", "conversation-001", taskId, agentName,
                AgentTokenStreamContext.PHASE_SUB_AGENT);
    }

    private AgentTokenStreamContext preWorkflowContext() {
        return new AgentTokenStreamContext(
                "wfi-001", "conversation-001", null, "intent-recognition-model",
                AgentTokenStreamContext.PHASE_INTENT_RECOGNITION);
    }

    private AgentTokenStreamContext summaryContext() {
        return new AgentTokenStreamContext(
                "wfi-001", "conversation-001", null, "workflow-summary-agent",
                AgentTokenStreamContext.PHASE_SUMMARY);
    }

    private void awaitEventCount(RecordingPublisher publisher,
                                 int expectedCount,
                                 Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (publisher.events().size() < expectedCount && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertThat(publisher.events()).hasSizeGreaterThanOrEqualTo(expectedCount);
    }

    private String concatenatedText(List<PublishedEvent> events) {
        return events.stream()
                .map(PublishedEvent::data)
                .filter(data -> !Boolean.TRUE.equals(data.get("last")))
                .map(data -> String.valueOf(data.get("content")))
                .reduce("", String::concat);
    }

    private String textForStream(List<PublishedEvent> events, String streamId) {
        return concatenatedText(events.stream()
                .filter(event -> streamId.equals(event.data().get("streamId")))
                .toList());
    }

    private record Fixture(RecordingPublisher publisher,
                           ScheduledExecutorService scheduler,
                           WorkflowAgentTokenStreamSink sink) implements AutoCloseable {

        @Override
        public void close() {
            sink.flushBeforeShutdown();
            scheduler.shutdownNow();
        }
    }

    private static final class RecordingPublisher implements WorkflowEventPublisher {

        private final List<PublishedEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void publish(String workflowInstanceId,
                            String conversationId,
                            long executionFenceToken,
                            WorkflowSseEventType type,
                            String node,
                            Map<String, Object> data) {
            events.add(new PublishedEvent(type, node, data));
        }

        @Override
        public void flushPersistedEvents(String workflowInstanceId) {
        }

        @Override
        public void completeSubscribers(String workflowInstanceId) {
        }

        private List<PublishedEvent> events() {
            return List.copyOf(events);
        }
    }

    private record PublishedEvent(WorkflowSseEventType type,
                                  String node,
                                  Map<String, Object> data) {
    }
}
