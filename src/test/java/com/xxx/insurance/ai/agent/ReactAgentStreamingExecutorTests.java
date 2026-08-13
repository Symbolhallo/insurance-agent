package com.xxx.insurance.ai.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReactAgentStreamingExecutorTests {

    @Test
    void usesSingleLowLevelStreamBecauseFinalStateIsRequired() throws Exception {
        ReactAgent reactAgent = mock(ReactAgent.class);
        AssistantMessage finalAnswer = AssistantMessage.builder().content("最终回答").build();
        OverAllState finalState = new OverAllState(Map.of("messages", List.of(finalAnswer)));
        when(reactAgent.stream("问题")).thenReturn(Flux.just(new StreamingOutput<>(
                finalAnswer, "done", "test-agent", finalState, OutputType.AGENT_MODEL_FINISHED)));

        AssistantMessage result = new ReactAgentStreamingExecutor(mock(AgentTokenStreamSink.class))
                .execute(reactAgent, "问题");

        assertThat(result).isSameAs(finalAnswer);
        verify(reactAgent).stream("问题");
        org.mockito.Mockito.verifyNoMoreInteractions(reactAgent);
    }

    @Test
    void extractsLastAssistantMessageFromFinalStreamState() throws Exception {
        ReactAgent reactAgent = mock(ReactAgent.class);
        AssistantMessage firstRound = AssistantMessage.builder().content("准备调用工具").build();
        AssistantMessage finalAnswer = AssistantMessage.builder().content("审核前完整回答").build();
        OverAllState finalState = new OverAllState(Map.of(
                "messages", List.of(new UserMessage("问题"), firstRound, finalAnswer)));
        StreamingOutput<String> output = new StreamingOutput<>(
                finalAnswer, "done", "test-agent", finalState, OutputType.AGENT_MODEL_FINISHED);
        when(reactAgent.stream("问题")).thenReturn(Flux.just(output));

        AssistantMessage result = new ReactAgentStreamingExecutor(mock(AgentTokenStreamSink.class))
                .execute(reactAgent, "问题");

        assertThat(result.getText()).isEqualTo("审核前完整回答");
    }

    @Test
    void rejectsFrameworkWrappedStreamingModelException() throws Exception {
        ReactAgent reactAgent = mock(ReactAgent.class);
        AgentTokenStreamSink sink = mock(AgentTokenStreamSink.class);
        AgentTokenStreamContext context = new AgentTokenStreamContext(
                "wfi-001", "conversation-001", "task-1", "test-agent", "SUB_AGENT");
        AssistantMessage wrappedError = AssistantMessage.builder()
                .content("Exception: upstream credential and request details")
                .build();
        OverAllState finalState = new OverAllState(Map.of("messages", List.of(wrappedError)));
        when(reactAgent.stream("问题")).thenReturn(Flux.just(new StreamingOutput<>(
                wrappedError, "done", "test-agent", finalState, OutputType.AGENT_MODEL_FINISHED)));

        assertThatThrownBy(() -> new ReactAgentStreamingExecutor(sink)
                .execute(reactAgent, "问题", context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ReactAgent streaming model call failed");
        verify(sink).abort(eq(context), anyString());
        verify(sink, never()).complete(eq(context), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void publishesOnlyLiveModelChunksAndOneCompletionMarker() throws Exception {
        ReactAgent reactAgent = mock(ReactAgent.class);
        AgentTokenStreamSink sink = mock(AgentTokenStreamSink.class);
        AgentTokenStreamContext context = new AgentTokenStreamContext(
                "wfi-001", "conversation-001", "task-1", "test-agent", "SUB_AGENT");
        AssistantMessage firstChunk = AssistantMessage.builder().content("逐").build();
        AssistantMessage secondChunk = AssistantMessage.builder().content("Token").build();
        AssistantMessage finalAnswer = AssistantMessage.builder().content("逐Token").build();
        OverAllState finalState = new OverAllState(Map.of("messages", List.of(finalAnswer)));
        when(reactAgent.stream("问题")).thenReturn(Flux.just(
                new StreamingOutput<>(firstChunk, "model", "test-agent", new OverAllState(),
                        OutputType.AGENT_MODEL_STREAMING),
                new StreamingOutput<>(secondChunk, "model", "test-agent", new OverAllState(),
                        OutputType.AGENT_MODEL_STREAMING),
                new StreamingOutput<>(finalAnswer, "done", "test-agent", finalState,
                        OutputType.AGENT_MODEL_FINISHED)));

        AssistantMessage result = new ReactAgentStreamingExecutor(sink)
                .execute(reactAgent, "问题", context);

        assertThat(result.getText()).isEqualTo("逐Token");
        verify(sink).publishToken(org.mockito.ArgumentMatchers.eq(context),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("逐"));
        verify(sink).publishToken(org.mockito.ArgumentMatchers.eq(context),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(2L),
                org.mockito.ArgumentMatchers.eq("Token"));
        verify(sink).complete(org.mockito.ArgumentMatchers.eq(context),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(2L));
    }
}
