package com.xxx.insurance.ai.workflow.job;

import com.xxx.insurance.ai.workflow.checkpoint.OceanBaseCheckpointSaver;
import com.xxx.insurance.ai.workflow.service.LocalDbWorkflowSseEventService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorkflowPersistenceCleanupJobTests {

    @Test
    void cleansCheckpointAndSseEvents() {
        OceanBaseCheckpointSaver checkpointSaver = mock(OceanBaseCheckpointSaver.class);
        LocalDbWorkflowSseEventService sseEventService = mock(LocalDbWorkflowSseEventService.class);

        new WorkflowPersistenceCleanupJob(checkpointSaver, sseEventService).cleanExpiredData();

        verify(checkpointSaver).purgeExpired(any(Instant.class));
        verify(sseEventService).purgeExpiredEvents(any(Instant.class));
    }

    @Test
    void stillCleansSseEventsWhenCheckpointCleanupFails() {
        OceanBaseCheckpointSaver checkpointSaver = mock(OceanBaseCheckpointSaver.class);
        LocalDbWorkflowSseEventService sseEventService = mock(LocalDbWorkflowSseEventService.class);
        doThrow(new IllegalStateException("checkpoint unavailable"))
                .when(checkpointSaver).purgeExpired(any(Instant.class));

        new WorkflowPersistenceCleanupJob(checkpointSaver, sseEventService).cleanExpiredData();

        verify(sseEventService).purgeExpiredEvents(any(Instant.class));
    }
}
