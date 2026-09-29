package com.xxx.insurance.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentScopeModelExecutorTests {

    @Test
    void publishesAndAggregatesStructuredModelChunks() {
        Model model = mock(Model.class);
        AgentTokenStreamSink sink = mock(AgentTokenStreamSink.class);
        AgentTokenStreamContext context = new AgentTokenStreamContext(
                "wfi-001", "conversation-001", null,
                "context-alignment-model", AgentTokenStreamContext.PHASE_CONTEXT_ALIGNMENT);
        when(model.stream(anyList(), anyList(), isNull())).thenReturn(Flux.just(
                response("{\"rewritten"),
                response("Question\":\"测试\"}")));

        String result = new AgentScopeModelExecutor(model, sink, new ObjectMapper())
                .execute(List.of(new UserMessage("测试")), context);

        assertThat(result).isEqualTo("{\"rewrittenQuestion\":\"测试\"}");
        verify(sink).publishToken(eq(context), anyString(), eq(1L), eq("{\"rewritten"));
        verify(sink).publishToken(eq(context), anyString(), eq(2L), eq("Question\":\"测试\"}"));
        verify(sink).complete(eq(context), anyString(), eq(2L));
    }

    @Test
    void repairsOnlyMissingStructuredObjectStartAfterAggregation() {
        Model model = mock(Model.class);
        AgentTokenStreamSink sink = mock(AgentTokenStreamSink.class);
        AgentTokenStreamContext context = new AgentTokenStreamContext(
                "wfi-001", "conversation-001", null,
                "context-alignment-model", AgentTokenStreamContext.PHASE_CONTEXT_ALIGNMENT);
        when(model.stream(anyList(), anyList(), isNull())).thenReturn(Flux.just(
                response("\"confirmedInformation\":{},"),
                response("\"rewrittenQuestion\":\"测试\"}")));

        String result = new AgentScopeModelExecutor(model, sink, new ObjectMapper())
                .execute(List.of(new UserMessage("测试")), context);

        assertThat(result).isEqualTo(
                "{\"confirmedInformation\":{},\"rewrittenQuestion\":\"测试\"}");
    }

    @Test
    void abortsBufferedDeliveryWhenModelStreamFails() {
        Model model = mock(Model.class);
        AgentTokenStreamSink sink = mock(AgentTokenStreamSink.class);
        AgentTokenStreamContext context = new AgentTokenStreamContext(
                "wfi-001", "conversation-001", null,
                "context-alignment-model", AgentTokenStreamContext.PHASE_CONTEXT_ALIGNMENT);
        when(model.stream(anyList(), anyList(), isNull())).thenReturn(Flux.concat(
                Flux.just(response("部分正文")),
                Flux.error(new IllegalStateException("upstream failed"))));

        assertThatThrownBy(() -> new AgentScopeModelExecutor(model, sink, new ObjectMapper())
                .execute(List.of(new UserMessage("测试")), context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("upstream failed");

        verify(sink).publishToken(eq(context), anyString(), eq(1L), eq("部分正文"));
        verify(sink).abort(eq(context), anyString());
        verify(sink, never()).complete(eq(context), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    private ChatResponse response(String content) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(content).build()))
                .build();
    }
}
