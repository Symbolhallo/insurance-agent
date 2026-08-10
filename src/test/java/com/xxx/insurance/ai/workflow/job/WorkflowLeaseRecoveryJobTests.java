package com.xxx.insurance.ai.workflow.job;

import com.xxx.insurance.ai.workflow.mapper.WorkflowExecutionMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorkflowLeaseRecoveryJobTests {

    @Test
    void releasesBothExpiredTransientClaims() {
        WorkflowExecutionMapper mapper = mock(WorkflowExecutionMapper.class);

        new WorkflowLeaseRecoveryJob(mapper).recoverExpiredClaims();

        verify(mapper).recoverExpiredConfirming(any(Instant.class));
        verify(mapper).recoverExpiredResuming(any(Instant.class));
    }
}
