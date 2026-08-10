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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReactAgentStreamingExecutorTests {

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

        AssistantMessage result = new ReactAgentStreamingExecutor().execute(reactAgent, "问题");

        assertThat(result.getText()).isEqualTo("审核前完整回答");
    }

    @Test
    void rejectsFrameworkWrappedStreamingModelException() throws Exception {
        ReactAgent reactAgent = mock(ReactAgent.class);
        AssistantMessage wrappedError = AssistantMessage.builder()
                .content("Exception: upstream credential and request details")
                .build();
        OverAllState finalState = new OverAllState(Map.of("messages", List.of(wrappedError)));
        when(reactAgent.stream("问题")).thenReturn(Flux.just(new StreamingOutput<>(
                wrappedError, "done", "test-agent", finalState, OutputType.AGENT_MODEL_FINISHED)));

        assertThatThrownBy(() -> new ReactAgentStreamingExecutor().execute(reactAgent, "问题"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ReactAgent streaming model call failed");
    }
}
