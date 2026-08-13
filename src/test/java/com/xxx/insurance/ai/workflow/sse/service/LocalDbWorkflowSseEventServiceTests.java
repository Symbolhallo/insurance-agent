package com.xxx.insurance.ai.workflow.sse.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.workflow.sse.config.WorkflowSseProperties;
import com.xxx.insurance.ai.workflow.config.WorkflowLifecycleProperties;
import com.xxx.insurance.ai.workflow.mapper.WorkflowExecutionMapper;
import com.xxx.insurance.ai.workflow.sse.mapper.WorkflowSseEventMapper;
import com.xxx.insurance.ai.workflow.model.WorkflowInstanceExecutionView;
import com.xxx.insurance.ai.workflow.sse.model.WorkflowSseEventRecord;
import com.xxx.insurance.ai.workflow.sse.model.WorkflowSseEventType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LocalDbWorkflowSseEventServiceTests {

    @Test
    void defaultsReplayRetentionToTenMinutes() {
        WorkflowSseProperties properties = new WorkflowSseProperties(null, null, null, null, 0);

        assertThat(properties.eventRetention()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void persistsEventWithWorkflowScopedIdAndTenMinuteExpiry() {
        WorkflowSseEventMapper eventMapper = mock(WorkflowSseEventMapper.class);
        when(eventMapper.allocateExecutionSequence(
                org.mockito.ArgumentMatchers.eq("wfi-001"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(3L),
                any(Instant.class))).thenReturn(1);
        when(eventMapper.lastAllocatedSequence()).thenReturn(3L);
        LocalDbWorkflowSseEventService service = service(eventMapper, mock(WorkflowExecutionMapper.class));

        service.publish(
                "wfi-001", "conversation-001", 3L, WorkflowSseEventType.STAGE,
                "planner-agent", Map.of("status", "SUCCESS"));

        ArgumentCaptor<WorkflowSseEventRecord> recordCaptor = ArgumentCaptor.forClass(WorkflowSseEventRecord.class);
        verify(eventMapper).insert(recordCaptor.capture());
        WorkflowSseEventRecord record = recordCaptor.getValue();
        assertThat(record.eventId()).isEqualTo("wfi-001:3");
        assertThat(record.sequenceNo()).isEqualTo(3);
        assertThat(record.payloadJson()).contains("SUCCESS");
        assertThat(Duration.between(record.createdAt(), record.expireAt())).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void staleExecutionFenceCannotAppendSseEvent() {
        WorkflowSseEventMapper eventMapper = mock(WorkflowSseEventMapper.class);
        when(eventMapper.allocateExecutionSequence(
                anyString(), anyString(), org.mockito.ArgumentMatchers.eq(2L), any(Instant.class)))
                .thenReturn(0);

        service(eventMapper, mock(WorkflowExecutionMapper.class)).publish(
                "wfi-001", "conversation-001", 2L,
                WorkflowSseEventType.STAGE, "planner", Map.of("status", "RUNNING"));

        verify(eventMapper, never()).insert(any());
    }

    @Test
    void rejectsLastEventIdBelongingToAnotherWorkflow() {
        LocalDbWorkflowSseEventService service = service(
                mock(WorkflowSseEventMapper.class), mock(WorkflowExecutionMapper.class));

        assertThatThrownBy(() -> service.reconnect("wfi-001", "wfi-002:5"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void replaysEventThatHasNotReachedTenMinuteExpiry() {
        WorkflowSseEventMapper eventMapper = mock(WorkflowSseEventMapper.class);
        WorkflowExecutionMapper executionMapper = mock(WorkflowExecutionMapper.class);
        Instant createdAt = Instant.now().minus(Duration.ofMinutes(9));
        WorkflowSseEventRecord event = new WorkflowSseEventRecord(
                "wfi-001:5", "wfi-001", "conversation-001", 5L, "stage", "summary",
                "{\"status\":\"SUCCESS\"}", createdAt, createdAt.plus(Duration.ofMinutes(10)));
        when(executionMapper.findInstance("wfi-001")).thenReturn(new WorkflowInstanceExecutionView(
                "wfi-001", "conversation-001", "SUCCESS", createdAt));
        when(eventMapper.findHighWatermark("wfi-001")).thenReturn(5L);
        when(eventMapper.findReplayEvents(any(), any(Long.class), any(Instant.class)))
                .thenReturn(List.of(event));

        assertThat(service(eventMapper, executionMapper).reconnect("wfi-001", "wfi-001:4"))
                .isNotNull();
        verify(eventMapper).findReplayEvents(
                org.mockito.ArgumentMatchers.eq("wfi-001"),
                org.mockito.ArgumentMatchers.eq(4L),
                any(Instant.class));
    }

    @Test
    void returnsGoneWhenTenMinuteReplayRangeHasExpired() {
        WorkflowSseEventMapper eventMapper = mock(WorkflowSseEventMapper.class);
        WorkflowExecutionMapper executionMapper = mock(WorkflowExecutionMapper.class);
        when(executionMapper.findInstance("wfi-001")).thenReturn(new WorkflowInstanceExecutionView(
                "wfi-001", "conversation-001", "SUCCESS", Instant.parse("2026-08-10T00:00:00Z")));
        when(eventMapper.findHighWatermark("wfi-001")).thenReturn(8L);
        when(eventMapper.findReplayEvents(any(), any(Long.class), any(Instant.class))).thenReturn(List.of());
        LocalDbWorkflowSseEventService service = service(eventMapper, executionMapper);

        assertThatThrownBy(() -> service.reconnect("wfi-001", "wfi-001:4"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.GONE));
    }

    @Test
    void delegatesExpiredEventDeletionToExistingMapperSql() {
        WorkflowSseEventMapper eventMapper = mock(WorkflowSseEventMapper.class);
        when(eventMapper.deleteExpiredEvents(any(Instant.class))).thenReturn(3);
        LocalDbWorkflowSseEventService service = service(eventMapper, mock(WorkflowExecutionMapper.class));
        Instant now = Instant.now();

        assertThat(service.purgeExpiredEvents(now)).isEqualTo(3);
        verify(eventMapper).deleteExpiredEvents(now);
    }

    @Test
    void subscribesClaimedConfirmationBeforeGraphResume() {
        WorkflowSseEventMapper eventMapper = mock(WorkflowSseEventMapper.class);
        WorkflowExecutionMapper executionMapper = mock(WorkflowExecutionMapper.class);
        when(executionMapper.findInstance("wfi-001")).thenReturn(new WorkflowInstanceExecutionView(
                "wfi-001", "conversation-001", "CONFIRMING", Instant.parse("2026-08-10T00:00:00Z")));
        when(eventMapper.findReplayEvents(any(), any(Long.class), any(Instant.class))).thenReturn(List.of());

        assertThat(service(eventMapper, executionMapper)
                .subscribeConfirmationResume("wfi-001", "wfi-001:4"))
                .isNotNull();
    }

    @Test
    void rejectsConfirmationStreamForNonWaitingInstance() {
        WorkflowExecutionMapper executionMapper = mock(WorkflowExecutionMapper.class);
        when(executionMapper.findInstance("wfi-001")).thenReturn(new WorkflowInstanceExecutionView(
                "wfi-001", "conversation-001", "SUCCESS", Instant.parse("2026-08-10T00:00:00Z")));

        assertThatThrownBy(() -> service(mock(WorkflowSseEventMapper.class), executionMapper)
                .subscribeConfirmationResume("wfi-001", "wfi-001:4"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void advancesDatabasePollingCursorAfterDeliveringCrossInstanceEvent() {
        WorkflowSseEventMapper eventMapper = mock(WorkflowSseEventMapper.class);
        LocalDbWorkflowSseEventService service = service(eventMapper, mock(WorkflowExecutionMapper.class));
        WorkflowSseEventRecord event = new WorkflowSseEventRecord(
                "wfi-001:3", "wfi-001", "conversation-001", 3L, "stage", "planner",
                "{\"status\":\"SUCCESS\"}", Instant.now(), Instant.now().plus(Duration.ofMinutes(10)));
        when(eventMapper.findReplayEvents(any(), any(Long.class), any(Instant.class)))
                .thenReturn(List.of(event), List.of());
        service.subscribeNewRun("wfi-001");

        service.pollPersistedEvents();
        service.pollPersistedEvents();

        ArgumentCaptor<Long> sequenceCaptor = ArgumentCaptor.forClass(Long.class);
        verify(eventMapper, org.mockito.Mockito.times(2)).findReplayEvents(
                org.mockito.ArgumentMatchers.eq("wfi-001"), sequenceCaptor.capture(), any(Instant.class));
        assertThat(sequenceCaptor.getAllValues()).containsExactly(0L, 3L);
    }

    @Test
    void flushSendsHumanConfirmBeforeTerminalEventClosesSubscriber() {
        WorkflowSseEventMapper eventMapper = mock(WorkflowSseEventMapper.class);
        LocalDbWorkflowSseEventService service = service(eventMapper, mock(WorkflowExecutionMapper.class));
        WorkflowSseEventRecord event = new WorkflowSseEventRecord(
                "wfi-001:4", "wfi-001", "conversation-001", 4L,
                WorkflowSseEventType.HUMAN_CONFIRM.eventName(), "human-confirm-product",
                "{\"status\":\"WAITING_CONFIRM\"}", Instant.now(),
                Instant.now().plus(Duration.ofMinutes(10)));
        when(eventMapper.findReplayEvents(any(), any(Long.class), any(Instant.class)))
                .thenReturn(List.of(event));
        service.subscribeNewRun("wfi-001");

        service.flushPersistedEvents("wfi-001");

        verify(eventMapper).findReplayEvents(
                org.mockito.ArgumentMatchers.eq("wfi-001"),
                org.mockito.ArgumentMatchers.eq(0L), any(Instant.class));
        clearInvocations(eventMapper);
        service.pollPersistedEvents();
        verifyNoInteractions(eventMapper);
    }

    private LocalDbWorkflowSseEventService service(WorkflowSseEventMapper eventMapper,
                                                   WorkflowExecutionMapper executionMapper) {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(mock(TransactionStatus.class));
        return new LocalDbWorkflowSseEventService(
                eventMapper,
                executionMapper,
                new ObjectMapper().findAndRegisterModules(),
                new WorkflowSseProperties(
                        Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMillis(500), null, 0),
                new WorkflowLifecycleProperties(),
                transactionManager);
    }
}
