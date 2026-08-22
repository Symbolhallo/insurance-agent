package com.xxx.insurance.ai.workflow.job;

import com.xxx.insurance.ai.workflow.config.WorkflowLifecycleProperties;
import com.xxx.insurance.ai.workflow.mapper.WorkflowExecutionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorkflowLeaseRecoveryJobTests {

    @Test
    void releasesBothExpiredTransientClaims() {
        WorkflowExecutionMapper mapper = mock(WorkflowExecutionMapper.class);

        new WorkflowLeaseRecoveryJob(mapper, lifecycleProperties()).recoverExpiredClaims();

        verify(mapper).recoverExpiredConfirming(any(Instant.class));
        verify(mapper).recoverExpiredResuming(any(Instant.class));
        verify(mapper).deleteExpiredInvalidConversationLocks(any(Instant.class));
    }

    @Test
    void renewsOnlyLeasesOwnedByCurrentInstanceForConfiguredDuration() {
        WorkflowExecutionMapper mapper = mock(WorkflowExecutionMapper.class);
        WorkflowLifecycleProperties properties = lifecycleProperties();
        WorkflowLeaseRecoveryJob job = new WorkflowLeaseRecoveryJob(mapper, properties);
        ArgumentCaptor<Instant> leaseUntil = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);

        job.renewOwnedLeases();

        verify(mapper).renewOwnedExecutionLeases(
                eq("instance-a"), leaseUntil.capture(), now.capture());
        assertThat(Duration.between(now.getValue(), leaseUntil.getValue()))
                .isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void defersHeartbeatWhenDatabaseRowIsTemporarilyLocked() {
        WorkflowExecutionMapper mapper = mock(WorkflowExecutionMapper.class);
        WorkflowLifecycleProperties properties = lifecycleProperties();
        WorkflowLeaseRecoveryJob job = new WorkflowLeaseRecoveryJob(mapper, properties);
        doThrow(new CannotAcquireLockException("debug transaction holds workflow row"))
                .when(mapper)
                .renewOwnedExecutionLeases(eq("instance-a"), any(Instant.class), any(Instant.class));

        assertThatCode(job::renewOwnedLeases).doesNotThrowAnyException();
        verify(mapper).renewOwnedExecutionLeases(
                eq("instance-a"), any(Instant.class), any(Instant.class));
    }

    private WorkflowLifecycleProperties lifecycleProperties() {
        WorkflowLifecycleProperties properties = new WorkflowLifecycleProperties();
        properties.setInstanceId("instance-a");
        properties.setExecutionLease(Duration.ofMinutes(15));
        properties.setClaimLease(Duration.ofMinutes(2));
        properties.setHeartbeatInterval(Duration.ofMinutes(1));
        return properties;
    }
}
