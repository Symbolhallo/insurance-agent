package com.xxx.insurance.ai.workflow.job;

import com.xxx.insurance.ai.workflow.checkpoint.OceanBaseCheckpointSaver;
import com.xxx.insurance.ai.workflow.sse.service.LocalDbWorkflowSseEventService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorkflowPersistenceCleanupJobTests {

    @Test
    void cleansCheckpointAndSseEventsOnIndependentSchedules() {
        OceanBaseCheckpointSaver checkpointSaver = mock(OceanBaseCheckpointSaver.class);
        LocalDbWorkflowSseEventService sseEventService = mock(LocalDbWorkflowSseEventService.class);
        WorkflowPersistenceCleanupJob job = new WorkflowPersistenceCleanupJob(checkpointSaver, sseEventService);

        job.cleanExpiredCheckpoints();
        job.cleanExpiredSseEvents();

        verify(checkpointSaver).purgeExpired(any(Instant.class));
        verify(sseEventService).purgeExpiredEvents(any(Instant.class));
    }

    @Test
    void checkpointCleanupFailureDoesNotAffectIndependentSseCleanup() {
        OceanBaseCheckpointSaver checkpointSaver = mock(OceanBaseCheckpointSaver.class);
        LocalDbWorkflowSseEventService sseEventService = mock(LocalDbWorkflowSseEventService.class);
        doThrow(new IllegalStateException("checkpoint unavailable"))
                .when(checkpointSaver).purgeExpired(any(Instant.class));

        WorkflowPersistenceCleanupJob job = new WorkflowPersistenceCleanupJob(checkpointSaver, sseEventService);
        job.cleanExpiredCheckpoints();
        job.cleanExpiredSseEvents();

        verify(sseEventService).purgeExpiredEvents(any(Instant.class));
    }
}
