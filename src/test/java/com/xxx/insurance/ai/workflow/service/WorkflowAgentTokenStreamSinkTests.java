package com.xxx.insurance.ai.workflow.service;

import com.xxx.insurance.ai.agent.AgentTokenStreamContext;
import com.xxx.insurance.ai.workflow.model.WorkflowSseEventType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorkflowAgentTokenStreamSinkTests {

    @Test
    void publishesLiveSubAgentChunkWithParallelStreamIdentity() {
        WorkflowEventPublisher eventPublisher = mock(WorkflowEventPublisher.class);
        WorkflowAgentTokenStreamSink sink = new WorkflowAgentTokenStreamSink(eventPublisher);
        AgentTokenStreamContext context = new AgentTokenStreamContext(
                "wfi-001", "conversation-001", "task-2", "knowledge-qa-agent",
                AgentTokenStreamContext.PHASE_SUB_AGENT);

        sink.publishToken(context, "stream-001", 3, "现金价值");

        ArgumentCaptor<Map<String, Object>> data = mapCaptor();
        verify(eventPublisher).publish(
                eq("wfi-001"), eq("conversation-001"), eq(WorkflowSseEventType.AGENT_STREAM),
                eq("agent-invoke"), data.capture());
        assertThat(data.getValue())
                .containsEntry("streamId", "stream-001")
                .containsEntry("taskId", "task-2")
                .containsEntry("agentName", "knowledge-qa-agent")
                .containsEntry("phase", "SUB_AGENT")
                .containsEntry("content", "现金价值")
                .containsEntry("chunkIndex", 3L)
                .containsEntry("last", false)
                .containsEntry("deliveryMode", "LIVE_MODEL_STREAM");
    }

    @Test
    void publishesSummaryCompletionWithoutDuplicatingAnswer() {
        WorkflowEventPublisher eventPublisher = mock(WorkflowEventPublisher.class);
        WorkflowAgentTokenStreamSink sink = new WorkflowAgentTokenStreamSink(eventPublisher);
        AgentTokenStreamContext context = new AgentTokenStreamContext(
                "wfi-001", "conversation-001", null, "workflow-summary-agent",
                AgentTokenStreamContext.PHASE_SUMMARY);

        sink.complete(context, "stream-summary-001", 8);

        ArgumentCaptor<Map<String, Object>> data = mapCaptor();
        verify(eventPublisher).publish(
                eq("wfi-001"), eq("conversation-001"), eq(WorkflowSseEventType.AGENT_STREAM),
                eq("summary"), data.capture());
        assertThat(data.getValue())
                .doesNotContainKey("taskId")
                .containsEntry("content", "")
                .containsEntry("chunkIndex", 8L)
                .containsEntry("last", true)
                .containsEntry("phase", "SUMMARY");
    }

    @Test
    void routesPreWorkflowModelChunkToItsBusinessNode() {
        WorkflowEventPublisher eventPublisher = mock(WorkflowEventPublisher.class);
        WorkflowAgentTokenStreamSink sink = new WorkflowAgentTokenStreamSink(eventPublisher);
        AgentTokenStreamContext context = new AgentTokenStreamContext(
                "wfi-001", "conversation-001", null, "intent-recognition-model",
                AgentTokenStreamContext.PHASE_INTENT_RECOGNITION);

        sink.publishToken(context, "stream-intent-001", 1, "{\"intentions\"");

        verify(eventPublisher).publish(
                eq("wfi-001"), eq("conversation-001"), eq(WorkflowSseEventType.AGENT_STREAM),
                eq("intent-recognition"), org.mockito.ArgumentMatchers.anyMap());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass((Class) Map.class);
    }
}
