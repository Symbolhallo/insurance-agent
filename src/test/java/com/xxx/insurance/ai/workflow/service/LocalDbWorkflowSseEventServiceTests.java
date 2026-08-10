package com.xxx.insurance.ai.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.workflow.config.WorkflowSseProperties;
import com.xxx.insurance.ai.workflow.mapper.WorkflowExecutionMapper;
import com.xxx.insurance.ai.workflow.mapper.WorkflowSseEventMapper;
import com.xxx.insurance.ai.workflow.model.WorkflowInstanceExecutionView;
import com.xxx.insurance.ai.workflow.model.WorkflowSseEventRecord;
import com.xxx.insurance.ai.workflow.model.WorkflowSseEventType;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalDbWorkflowSseEventServiceTests {

    @Test
    void persistsEventWithWorkflowScopedIdAndSevenDayExpiry() {
        WorkflowSseEventMapper eventMapper = mock(WorkflowSseEventMapper.class);
        when(eventMapper.allocateSequence("wfi-001")).thenReturn(1);
        when(eventMapper.lastAllocatedSequence()).thenReturn(3L);
        LocalDbWorkflowSseEventService service = service(eventMapper, mock(WorkflowExecutionMapper.class));

        service.publish(
                "wfi-001", "conversation-001", WorkflowSseEventType.STAGE,
                "planner-agent", Map.of("status", "SUCCESS"));

        ArgumentCaptor<WorkflowSseEventRecord> recordCaptor = ArgumentCaptor.forClass(WorkflowSseEventRecord.class);
        verify(eventMapper).insert(recordCaptor.capture());
        WorkflowSseEventRecord record = recordCaptor.getValue();
        assertThat(record.eventId()).isEqualTo("wfi-001:3");
        assertThat(record.sequenceNo()).isEqualTo(3);
        assertThat(record.payloadJson()).contains("SUCCESS");
        assertThat(Duration.between(record.createdAt(), record.expireAt())).isEqualTo(Duration.ofDays(7));
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
    void returnsGoneWhenRequestedReplayRangeHasExpired() {
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
                "{\"status\":\"SUCCESS\"}", Instant.now(), Instant.now().plus(Duration.ofDays(7)));
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

    private LocalDbWorkflowSseEventService service(WorkflowSseEventMapper eventMapper,
                                                   WorkflowExecutionMapper executionMapper) {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(mock(TransactionStatus.class));
        return new LocalDbWorkflowSseEventService(
                eventMapper,
                executionMapper,
                new ObjectMapper().findAndRegisterModules(),
                new WorkflowSseProperties(Duration.ofMinutes(5), Duration.ofDays(7), Duration.ofMillis(500)),
                transactionManager);
    }
}
