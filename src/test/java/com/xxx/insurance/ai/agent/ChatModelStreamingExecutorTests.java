package com.xxx.insurance.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatModelStreamingExecutorTests {

    @Test
    void publishesAndAggregatesStructuredModelChunks() {
        ChatModel chatModel = mock(ChatModel.class);
        AgentTokenStreamSink sink = mock(AgentTokenStreamSink.class);
        AgentTokenStreamContext context = new AgentTokenStreamContext(
                "wfi-001", "conversation-001", null,
                "context-alignment-model", AgentTokenStreamContext.PHASE_CONTEXT_ALIGNMENT);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                response("{\"rewritten"),
                response("Question\":\"测试\"}")));

        String result = new ChatModelStreamingExecutor(sink, new ObjectMapper())
                .execute(chatModel, List.of(new UserMessage("测试")), context);

        assertThat(result).isEqualTo("{\"rewrittenQuestion\":\"测试\"}");
        verify(sink).publishToken(eq(context), anyString(), eq(1L), eq("{\"rewritten"));
        verify(sink).publishToken(eq(context), anyString(), eq(2L), eq("Question\":\"测试\"}"));
        verify(sink).complete(eq(context), anyString(), eq(2L));
    }

    @Test
    void repairsOnlyMissingStructuredObjectStartAfterAggregation() {
        ChatModel chatModel = mock(ChatModel.class);
        AgentTokenStreamSink sink = mock(AgentTokenStreamSink.class);
        AgentTokenStreamContext context = new AgentTokenStreamContext(
                "wfi-001", "conversation-001", null,
                "context-alignment-model", AgentTokenStreamContext.PHASE_CONTEXT_ALIGNMENT);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                response("\"confirmedInformation\":{},"),
                response("\"rewrittenQuestion\":\"测试\"}")));

        String result = new ChatModelStreamingExecutor(sink, new ObjectMapper())
                .execute(chatModel, List.of(new UserMessage("测试")), context);

        assertThat(result).isEqualTo(
                "{\"confirmedInformation\":{},\"rewrittenQuestion\":\"测试\"}");
    }

    @Test
    void abortsBufferedDeliveryWhenModelStreamFails() {
        ChatModel chatModel = mock(ChatModel.class);
        AgentTokenStreamSink sink = mock(AgentTokenStreamSink.class);
        AgentTokenStreamContext context = new AgentTokenStreamContext(
                "wfi-001", "conversation-001", null,
                "context-alignment-model", AgentTokenStreamContext.PHASE_CONTEXT_ALIGNMENT);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.concat(
                Flux.just(response("部分正文")),
                Flux.error(new IllegalStateException("upstream failed"))));

        assertThatThrownBy(() -> new ChatModelStreamingExecutor(sink, new ObjectMapper())
                .execute(chatModel, List.of(new UserMessage("测试")), context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("upstream failed");

        verify(sink).publishToken(eq(context), anyString(), eq(1L), eq("部分正文"));
        verify(sink).abort(eq(context), anyString());
        verify(sink, never()).complete(eq(context), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content(content).build())));
    }
}
