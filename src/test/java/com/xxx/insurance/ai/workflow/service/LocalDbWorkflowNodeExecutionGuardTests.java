package com.xxx.insurance.ai.workflow.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.workflow.config.WorkflowLifecycleProperties;
import com.xxx.insurance.ai.workflow.mapper.WorkflowExecutionMapper;
import com.xxx.insurance.ai.workflow.model.MainWorkflowStateKeys;
import com.xxx.insurance.ai.workflow.model.WorkflowNodeDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalDbWorkflowNodeExecutionGuardTests {

    @Test
    void rejectsNodeExecutionWhenLeaseFenceStartWriteIsLost() throws Exception {
        WorkflowExecutionMapper mapper = mock(WorkflowExecutionMapper.class);
        WorkflowLifecycleProperties properties = new WorkflowLifecycleProperties();
        properties.setInstanceId("instance-a");
        LocalDbWorkflowNodeExecutionGuard guard = new LocalDbWorkflowNodeExecutionGuard(
                mapper, new ObjectMapper(), properties);
        OverAllState state = new OverAllState(Map.of(
                MainWorkflowStateKeys.EXECUTION_FENCE_TOKEN, 7L,
                MainWorkflowStateKeys.WORKFLOW_STEP_IDS,
                Map.of(WorkflowNodeDefinition.PLANNER.code(), "step-001")));
        @SuppressWarnings("unchecked")
        Callable<Map<String, Object>> node = mock(Callable.class);
        when(mapper.updateStepStarted(eq("step-001"), eq("instance-a"), eq(7L), any(Instant.class)))
                .thenReturn(0);

        assertThatThrownBy(() -> guard.execute(WorkflowNodeDefinition.PLANNER, state, node))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lease was lost");

        verify(node, never()).call();
    }
}
