package com.xxx.insurance.ai.workflow.service;

import com.xxx.insurance.ai.workflow.mapper.WorkflowExecutionMapper;
import com.xxx.insurance.ai.workflow.model.WorkflowInstanceRecord;
import com.xxx.insurance.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

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
    }
}
