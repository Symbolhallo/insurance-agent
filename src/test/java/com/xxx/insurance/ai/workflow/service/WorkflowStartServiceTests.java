package com.xxx.insurance.ai.workflow.service;

import com.xxx.insurance.ai.workflow.mapper.WorkflowExecutionMapper;
import com.xxx.insurance.ai.workflow.model.WorkflowInstanceRecord;
import com.xxx.insurance.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WorkflowStartServiceTests {

    @Test
    void mapsDatabaseConversationOrRequestConflictToBusinessConflict() {
        WorkflowExecutionMapper mapper = mock(WorkflowExecutionMapper.class);
        doThrow(new DuplicateKeyException("duplicate conversation lock"))
                .when(mapper).insertConversationLock(any(), any(), any(), any(), any());
        WorkflowStartService service = new WorkflowStartService(mapper);
        Instant now = Instant.now();
        WorkflowInstanceRecord instance = new WorkflowInstanceRecord(
                "wfi-001", "main-workflow-v1", "conversation-001", "request-001", null,
                "RUNNING", "{}", "instance-a", now.plusSeconds(60), now);

        assertThatThrownBy(() -> service.start(instance, List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("会话仍有工作流");

        verify(mapper).deleteExpiredInvalidConversationLock("conversation-001", now);
        verify(mapper, never()).insertInstance(any());
    }

    @Test
    void removesExpiredInvalidLockBeforeAtomicallyStartingWorkflow() {
        WorkflowExecutionMapper mapper = mock(WorkflowExecutionMapper.class);
        WorkflowStartService service = new WorkflowStartService(mapper);
        Instant now = Instant.now();
        WorkflowInstanceRecord instance = new WorkflowInstanceRecord(
                "wfi-002", "main-workflow-v1", "conversation-002", "request-002", null,
                "RUNNING", "{}", "instance-a", now.plusSeconds(900), now);

        service.start(instance, List.of());

        InOrder order = inOrder(mapper);
        order.verify(mapper).deleteExpiredInvalidConversationLock("conversation-002", now);
        order.verify(mapper).insertConversationLock(
                "conversation-002", "wfi-002", "request-002", instance.leaseUntil(), now);
        order.verify(mapper).insertInstance(instance);
    }
}
