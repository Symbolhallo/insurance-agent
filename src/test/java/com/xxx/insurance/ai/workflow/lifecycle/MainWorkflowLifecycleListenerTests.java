package com.xxx.insurance.ai.workflow.lifecycle;

import com.alibaba.cloud.ai.graph.GraphLifecycleListener;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.xxx.insurance.ai.workflow.mapper.WorkflowExecutionMapper;
import com.xxx.insurance.ai.workflow.model.MainWorkflowRequest;
import com.xxx.insurance.ai.workflow.model.MainWorkflowStateKeys;
import com.xxx.insurance.ai.workflow.model.WorkflowNodeDefinition;
import com.xxx.insurance.ai.workflow.sse.model.WorkflowSseEventType;
import com.xxx.insurance.ai.workflow.sse.service.WorkflowEventPublisher;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MainWorkflowLifecycleListenerTests {

    @Test
    void publishesNodeLifecycleWithoutOwningLeaseFenceWrites() {
        WorkflowEventPublisher publisher = mock(WorkflowEventPublisher.class);
        MainWorkflowLifecycleListener listener = new MainWorkflowLifecycleListener(
                mock(WorkflowExecutionMapper.class), publisher);
        Map<String, Object> state = state();
        RunnableConfig config = RunnableConfig.builder().threadId("wfi-001").build();

        listener.before(WorkflowNodeDefinition.PLANNER.code(), state, config, 1L);
        listener.after(WorkflowNodeDefinition.PLANNER.code(), state, config, 2L);

        verify(publisher).publish(
                eq("wfi-001"), eq("conversation-001"), eq(7L),
                eq(WorkflowSseEventType.STAGE), eq(WorkflowNodeDefinition.PLANNER.code()),
                eq(Map.of("status", "RUNNING", "nodeName", WorkflowNodeDefinition.PLANNER.nodeName())));
        verify(publisher).publish(
                eq("wfi-001"), eq("conversation-001"), eq(7L),
                eq(WorkflowSseEventType.STAGE), eq(WorkflowNodeDefinition.PLANNER.code()),
                eq(Map.of("status", "SUCCESS", "nodeName", WorkflowNodeDefinition.PLANNER.nodeName())));
    }

    @Test
    void publishesSanitizedFailureEvent() {
        WorkflowEventPublisher publisher = mock(WorkflowEventPublisher.class);
        MainWorkflowLifecycleListener listener = new MainWorkflowLifecycleListener(
                mock(WorkflowExecutionMapper.class), publisher);
        Map<String, Object> state = state();

        listener.onError(
                WorkflowNodeDefinition.CONTEXT_ALIGNMENT.code(), state,
                new IllegalStateException("secret upstream response"), RunnableConfig.builder().build());

        verify(publisher).publish(
                eq("wfi-001"), eq("conversation-001"), eq(7L),
                eq(WorkflowSseEventType.STAGE), eq(WorkflowNodeDefinition.CONTEXT_ALIGNMENT.code()),
                eq(Map.of(
                        "status", "FAILED",
                        "nodeName", WorkflowNodeDefinition.CONTEXT_ALIGNMENT.nodeName(),
                        "message", "节点执行失败，请稍后重试或联系人工支持：上下文对齐")));
    }

    private Map<String, Object> state() {
        Map<String, Object> state = new HashMap<>();
        state.put(GraphLifecycleListener.EXECUTION_ID_KEY, "execution-001");
        state.put(MainWorkflowStateKeys.WORKFLOW_INSTANCE_ID, "wfi-001");
        state.put(MainWorkflowStateKeys.EXECUTION_FENCE_TOKEN, 7L);
        state.put(MainWorkflowStateKeys.REQUEST,
                new MainWorkflowRequest("测试问题", "conversation-001", "request-001"));
        return state;
    }
}
