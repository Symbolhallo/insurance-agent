package com.xxx.insurance.ai.workflow.checkpoint;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.workflow.checkpoint.config.GraphCheckpointProperties;
import com.xxx.insurance.ai.workflow.checkpoint.mapper.GraphCheckpointMapper;
import com.xxx.insurance.ai.workflow.checkpoint.model.GraphCheckpointRecord;
import com.xxx.insurance.ai.workflow.checkpoint.model.GraphCheckpointThreadRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class OceanBaseCheckpointSaverTests {

    private GraphCheckpointMapper mapper;

    private GraphCheckpointStateCodec codec;

    private GraphCheckpointProperties properties;

    private OceanBaseCheckpointSaver saver;

    @BeforeEach
    void setUp() {
        mapper = mock(GraphCheckpointMapper.class);
        codec = new GraphCheckpointStateCodec(new SpringAIJacksonStateSerializer(
                OverAllState::new,
                new ObjectMapper()));
        properties = new GraphCheckpointProperties();
        properties.setActiveRetention(Duration.ofDays(7));
        properties.setCompletedRetention(Duration.ofHours(24));
        properties.setStateSchemaVersion(1);
        properties.setMaxWriteRetries(3);
        saver = new OceanBaseCheckpointSaver(mapper, codec, properties);
    }

    @Test
    void persistsCheckpointWithThreadMetadataAndParent() {
        RunnableConfig config = config("wfi-001").withCheckPointId("cp-parent");
        Checkpoint checkpoint = checkpoint("cp-new", "node-a", "node-b", Map.of("answer", "ok"));
        GraphCheckpointThreadRecord thread = thread("wfi-001", "ACTIVE", "cp-latest", 4);
        when(mapper.findReadableThread(anyString(), any(Instant.class))).thenReturn(thread);
        when(mapper.advanceThreadVersion(
                anyString(), anyLong(), anyString(), anyString(), anyLong(), any(), any())).thenReturn(1);

        RunnableConfig result = saver.put(config, checkpoint);

        assertThat(result.checkPointId()).contains("cp-new");
        verify(mapper).insertThreadIfAbsent(
                org.mockito.ArgumentMatchers.eq("wfi-001"),
                org.mockito.ArgumentMatchers.eq("wfi-001"),
                org.mockito.ArgumentMatchers.eq("conversation-001"),
                org.mockito.ArgumentMatchers.eq("test-instance"),
                org.mockito.ArgumentMatchers.eq(1L),
                any(Instant.class),
                any(Instant.class));
        ArgumentCaptor<GraphCheckpointRecord> recordCaptor = ArgumentCaptor.forClass(GraphCheckpointRecord.class);
        verify(mapper).insertCheckpoint(recordCaptor.capture());
        GraphCheckpointRecord record = recordCaptor.getValue();
        assertThat(record.parentCheckpointId()).isEqualTo("cp-parent");
        assertThat(record.checkpointVersion()).isEqualTo(5);
        assertThat(codec.decode(record.statePayload(), record.stateContentType()))
                .containsEntry("answer", "ok");
    }

    @Test
    void defaultsActiveAndFailedToSevenDaysAndCompletedToTwentyFourHours() {
        GraphCheckpointProperties defaults = new GraphCheckpointProperties();

        assertThat(defaults.getActiveRetention()).isEqualTo(Duration.ofDays(7));
        assertThat(defaults.getCompletedRetention()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void appliesStatusSpecificExpiryWhenWorkflowReachesTerminalState() {
        when(mapper.updateWorkflowThreadStatuses(
                anyString(), anyLong(), anyString(), any(), any())).thenReturn(1);
        ArgumentCaptor<Instant> completedExpiry = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> completedAt = ArgumentCaptor.forClass(Instant.class);

        saver.markWorkflowCompleted("wfi-completed", 7L);

        verify(mapper).updateWorkflowThreadStatuses(
                org.mockito.ArgumentMatchers.eq("wfi-completed"),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("COMPLETED"),
                completedExpiry.capture(),
                completedAt.capture());
        assertThat(Duration.between(completedAt.getValue(), completedExpiry.getValue()))
                .isEqualTo(Duration.ofHours(24));

        ArgumentCaptor<Instant> failedExpiry = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> failedAt = ArgumentCaptor.forClass(Instant.class);
        saver.markWorkflowFailed("wfi-failed", 8L);

        verify(mapper).updateWorkflowThreadStatuses(
                org.mockito.ArgumentMatchers.eq("wfi-failed"),
                org.mockito.ArgumentMatchers.eq(8L),
                org.mockito.ArgumentMatchers.eq("FAILED"),
                failedExpiry.capture(),
                failedAt.capture());
        assertThat(Duration.between(failedAt.getValue(), failedExpiry.getValue()))
                .isEqualTo(Duration.ofDays(7));
    }

    @Test
    void retriesOptimisticVersionConflict() {
        GraphCheckpointThreadRecord versionOne = thread("wfi-001", "ACTIVE", "cp-1", 1);
        GraphCheckpointThreadRecord versionTwo = thread("wfi-001", "ACTIVE", "cp-2", 2);
        when(mapper.findReadableThread(anyString(), any(Instant.class)))
                .thenReturn(versionOne, versionTwo);
        when(mapper.advanceThreadVersion(
                anyString(), anyLong(), anyString(), anyString(), anyLong(), any(), any()))
                .thenReturn(0, 1);

        saver.put(config("wfi-001"), checkpoint("cp-3", "node-a", "node-b", Map.of("value", 3)));

        verify(mapper, times(2)).advanceThreadVersion(
                anyString(), anyLong(), anyString(), anyString(), anyLong(),
                any(Instant.class), any(Instant.class));
        ArgumentCaptor<GraphCheckpointRecord> recordCaptor = ArgumentCaptor.forClass(GraphCheckpointRecord.class);
        verify(mapper).insertCheckpoint(recordCaptor.capture());
        assertThat(recordCaptor.getValue().checkpointVersion()).isEqualTo(3);
        assertThat(recordCaptor.getValue().parentCheckpointId()).isEqualTo("cp-2");
    }

    @Test
    void rejectsCheckpointWhenExecutionFenceNoLongerOwnsLease() {
        when(mapper.findReadableThread(anyString(), any(Instant.class)))
                .thenReturn(thread("wfi-001", "ACTIVE", "cp-1", 1));
        when(mapper.advanceThreadVersion(
                anyString(), anyLong(), anyString(), anyString(), anyLong(), any(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> saver.put(
                config("wfi-001"), checkpoint("cp-2", "node-a", "node-b", Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("concurrent update exceeded retry limit");
        verify(mapper, never()).insertCheckpoint(any());
    }

    @Test
    void restoresLatestCheckpointFromSerializedState() {
        GraphCheckpointStateCodec.EncodedState encoded = codec.encode(Map.of("answer", "restored"));
        GraphCheckpointRecord record = new GraphCheckpointRecord(
                "cp-5",
                "wfi-001",
                "cp-4",
                5,
                "summary",
                "__END__",
                encoded.payload(),
                encoded.contentType(),
                1,
                Instant.now());
        when(mapper.findReadableThread(anyString(), any(Instant.class)))
                .thenReturn(thread("wfi-001", "COMPLETED", "cp-5", 5));
        when(mapper.findCheckpoint(anyString(), anyString(), any(Instant.class))).thenReturn(record);

        Checkpoint restored = saver.get(config("wfi-001")).orElseThrow();

        assertThat(restored.getId()).isEqualTo("cp-5");
        assertThat(restored.getState()).containsEntry("answer", "restored");
        assertThat(restored.getNextNodeId()).isEqualTo("__END__");
    }

    @Test
    void rejectsCheckpointWithNewerStateSchema() {
        GraphCheckpointStateCodec.EncodedState encoded = codec.encode(Map.of("answer", "future"));
        GraphCheckpointRecord record = new GraphCheckpointRecord(
                "cp-future", "wfi-001", null, 1, "node-a", "node-b",
                encoded.payload(), encoded.contentType(), 2, Instant.now());
        when(mapper.findCheckpoint(anyString(), anyString(), any(Instant.class))).thenReturn(record);

        RunnableConfig config = config("wfi-001").withCheckPointId("cp-future");

        assertThatThrownBy(() -> saver.get(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("newer than application");
    }

    @Test
    void releasesThreadAndPurgesExpiredRows() {
        GraphCheckpointStateCodec.EncodedState encoded = codec.encode(Map.of("value", 1));
        when(mapper.findCheckpoints(anyString(), any(Instant.class))).thenReturn(List.of(
                new GraphCheckpointRecord(
                        "cp-1", "wfi-001", null, 1, "node-a", "node-b",
                        encoded.payload(), encoded.contentType(), 1, Instant.now())));
        when(mapper.releaseThread(anyString(), any(Instant.class), any(Instant.class))).thenReturn(1);
        when(mapper.deleteExpiredCheckpoints(any(Instant.class))).thenReturn(4);
        when(mapper.deleteExpiredThreads(any(Instant.class))).thenReturn(2);

        assertThat(saver.release(config("wfi-001")).checkpoints()).hasSize(1);
        OceanBaseCheckpointSaver.PurgeResult purgeResult = saver.purgeExpired(Instant.now());

        assertThat(purgeResult.checkpointCount()).isEqualTo(4);
        assertThat(purgeResult.threadCount()).isEqualTo(2);
        org.mockito.InOrder deletionOrder = inOrder(mapper);
        deletionOrder.verify(mapper).deleteExpiredCheckpoints(any(Instant.class));
        deletionOrder.verify(mapper).deleteExpiredThreads(any(Instant.class));
    }

    @Test
    void requiresExplicitThreadId() {
        assertThatThrownBy(() -> saver.get(RunnableConfig.builder().build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threadId");
    }

    private RunnableConfig config(String threadId) {
        return RunnableConfig.builder()
                .threadId(threadId)
                .addMetadata(OceanBaseCheckpointSaver.METADATA_WORKFLOW_INSTANCE_ID, threadId)
                .addMetadata(OceanBaseCheckpointSaver.METADATA_CONVERSATION_ID, "conversation-001")
                .addMetadata(OceanBaseCheckpointSaver.METADATA_EXECUTION_OWNER, "test-instance")
                .addMetadata(OceanBaseCheckpointSaver.METADATA_EXECUTION_FENCE_TOKEN, 1L)
                .build();
    }

    private Checkpoint checkpoint(String id,
                                  String nodeId,
                                  String nextNodeId,
                                  Map<String, Object> state) {
        return Checkpoint.builder()
                .id(id)
                .nodeId(nodeId)
                .nextNodeId(nextNodeId)
                .state(state)
                .build();
    }

    private GraphCheckpointThreadRecord thread(String threadId,
                                               String status,
                                               String latestCheckpointId,
                                               long version) {
        Instant now = Instant.now();
        return new GraphCheckpointThreadRecord(
                threadId,
                threadId,
                "conversation-001",
                status,
                latestCheckpointId,
                version,
                now.plus(Duration.ofDays(7)),
                null,
                now,
                now);
    }
}
