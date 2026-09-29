package com.xxx.insurance.ai.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReactAgentStreamingExecutorTests {

    @Test
    void publishesAgentScopeTextEventsAndReturnsResultFromSameStream() {
        HarnessAgent agent = mock(HarnessAgent.class);
        AgentTokenStreamSink sink = mock(AgentTokenStreamSink.class);
        AgentTokenStreamContext context = new AgentTokenStreamContext(
                "wfi-001", "conversation-001", "task-1", "test-agent", "SUB_AGENT");
        AssistantMessage answer = new AssistantMessage("test-agent", "逐Token");
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.just(
                new TextBlockDeltaEvent("reply-1", "block-1", "逐"),
                new TextBlockDeltaEvent("reply-1", "block-1", "Token"),
                new AgentResultEvent(answer)));

        AssistantMessage result = new ReactAgentStreamingExecutor(sink).execute(agent, "问题", context);

        assertThat(result.getTextContent()).isEqualTo("逐Token");
        verify(sink).publishToken(eq(context), anyString(), eq(1L), eq("逐"));
        verify(sink).publishToken(eq(context), anyString(), eq(2L), eq("Token"));
        verify(sink).complete(eq(context), anyString(), eq(2L));
    }

    @Test
    void omitsNullOptionalFieldsFromAgentScopeRuntimeContext() {
        HarnessAgent agent = mock(HarnessAgent.class);
        AgentTokenStreamSink sink = mock(AgentTokenStreamSink.class);
        AgentTokenStreamContext context = new AgentTokenStreamContext(
                "wfi-001", "conversation-001", null, "workflow-planner-agent", "PLANNER");
        AssistantMessage answer = new AssistantMessage("workflow-planner-agent", "计划");
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(new AgentResultEvent(answer)));

        AssistantMessage result = new ReactAgentStreamingExecutor(sink).execute(agent, "规划", context);

        ArgumentCaptor<RuntimeContext> runtimeContext = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(agent).streamEvents(anyList(), runtimeContext.capture());
        assertThat(result.getTextContent()).isEqualTo("计划");
        assertThat(runtimeContext.getValue().getExtra())
                .containsEntry("workflowInstanceId", "wfi-001")
                .containsEntry("agentName", "workflow-planner-agent")
                .doesNotContainKey("taskId");
    }

    @Test
    void abortsReliableStreamWhenAgentScopeEventStreamFails() {
        HarnessAgent agent = mock(HarnessAgent.class);
        AgentTokenStreamSink sink = mock(AgentTokenStreamSink.class);
        AgentTokenStreamContext context = new AgentTokenStreamContext(
                "wfi-001", "conversation-001", "task-1", "test-agent", "SUB_AGENT");
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.error(new IllegalStateException("upstream failed")));

        assertThatThrownBy(() -> new ReactAgentStreamingExecutor(sink).execute(agent, "问题", context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("upstream failed");
        verify(sink).abort(eq(context), anyString());
    }
}
