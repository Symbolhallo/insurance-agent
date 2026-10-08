package com.xxx.insurance.ai.agent;

import com.xxx.insurance.ai.config.AiModelProperties;
import com.xxx.insurance.ai.memory.model.AgentInvocationRecord;
import com.xxx.insurance.ai.memory.model.AgentMemoryExchange;
import com.xxx.insurance.ai.memory.service.AgentMemoryService;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationAwareAgentExecutorTests {

    @Test
    void loadsHistoryAndAtomicallySavesSuccessfulExchange() {
        HarnessAgent agent = mock(HarnessAgent.class);
        AgentMemoryService memoryService = mock(AgentMemoryService.class);
        ReactAgentStreamingExecutor streamingExecutor = mock(ReactAgentStreamingExecutor.class);
        when(memoryService.isEnabled()).thenReturn(true);
        when(memoryService.getHistory("conversation-1")).thenReturn(List.of(new UserMessage("历史问题")));
        when(streamingExecutor.execute(eq(agent), any(List.class), eq(null)))
                .thenReturn(new AssistantMessage("## 产品定位\n内容"));
        ConversationAwareAgentExecutor executor = executor(memoryService, streamingExecutor);

        ConversationAgentExecutionResult result = executor.execute(
                agent, "product-analysis-agent", "pai-", "当前问题", "conversation-1",
                AgentExecutionContext.standalone("用户原话"),
                answer -> new ConversationAgentOutputInspection(false, List.of("风险提示")),
                "Product analysis model invocation failed");

        assertThat(result.invocationId()).startsWith("pai-");
        assertThat(result.memoryEnabled()).isTrue();
        assertThat(result.historyMessageCount()).isEqualTo(1);
        assertThat(result.outputFormatValid()).isFalse();
        assertThat(result.missingSections()).containsExactly("风险提示");
        ArgumentCaptor<AgentMemoryExchange> exchange = ArgumentCaptor.forClass(AgentMemoryExchange.class);
        ArgumentCaptor<AgentInvocationRecord> invocation = ArgumentCaptor.forClass(AgentInvocationRecord.class);
        verify(memoryService).saveSuccessfulExchange(exchange.capture(), invocation.capture());
        assertThat(exchange.getValue().userMessage().getTextContent()).isEqualTo("用户原话");
        assertThat(invocation.getValue().outputFormatValid()).isFalse();
        assertThat(invocation.getValue().missingSections()).containsExactly("风险提示");
    }

    @Test
    void dagExecutionSkipsConversationMemoryAndPublishesWithTaskContext() {
        HarnessAgent agent = mock(HarnessAgent.class);
        AgentMemoryService memoryService = mock(AgentMemoryService.class);
        ReactAgentStreamingExecutor streamingExecutor = mock(ReactAgentStreamingExecutor.class);
        when(memoryService.isEnabled()).thenReturn(true);
        when(streamingExecutor.execute(eq(agent), eq("标准化问题"), any(AgentTokenStreamContext.class)))
                .thenReturn(new AssistantMessage("知识回答"));
        ConversationAwareAgentExecutor executor = executor(memoryService, streamingExecutor);

        ConversationAgentExecutionResult result = executor.execute(
                agent, "knowledge-qa-agent", "kqa-", "标准化问题", "conversation-1",
                new AgentExecutionContext("wfi-1", "step-1", "用户原话", false, "task-1", true),
                null, "Knowledge QA model invocation failed");

        assertThat(result.memoryEnabled()).isFalse();
        assertThat(result.outputFormatValid()).isNull();
        verify(memoryService, never()).getHistory(any());
        verify(memoryService).saveSuccessfulInvocation(any());
        verify(streamingExecutor).execute(eq(agent), eq("标准化问题"),
                org.mockito.ArgumentMatchers.argThat(context ->
                        "wfi-1".equals(context.workflowInstanceId())
                                && "task-1".equals(context.taskId())
                                && "knowledge-qa-agent".equals(context.agentName())));
    }

    @Test
    void recordsFailureWithoutHidingOriginalCause() {
        HarnessAgent agent = mock(HarnessAgent.class);
        AgentMemoryService memoryService = mock(AgentMemoryService.class);
        ReactAgentStreamingExecutor streamingExecutor = mock(ReactAgentStreamingExecutor.class);
        when(memoryService.isEnabled()).thenReturn(true);
        IllegalStateException original = new IllegalStateException("model unavailable");
        when(streamingExecutor.execute(agent, "问题", null)).thenThrow(original);
        ConversationAwareAgentExecutor executor = executor(memoryService, streamingExecutor);

        assertThatThrownBy(() -> executor.execute(
                agent, "knowledge-qa-agent", "kqa-", "问题", "conversation-1",
                new AgentExecutionContext("wfi-1", "step-1", "原话", false, "task-1", false),
                null, "Knowledge QA model invocation failed"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Knowledge QA model invocation failed")
                .hasCause(original);

        ArgumentCaptor<AgentInvocationRecord> invocation = ArgumentCaptor.forClass(AgentInvocationRecord.class);
        verify(memoryService).saveFailedInvocation(invocation.capture());
        assertThat(invocation.getValue().status()).isEqualTo("FAILED");
        assertThat(invocation.getValue().userMessage()).isEqualTo("原话");
    }

    private ConversationAwareAgentExecutor executor(AgentMemoryService memoryService,
                                                     ReactAgentStreamingExecutor streamingExecutor) {
        AiModelProperties properties = new AiModelProperties();
        properties.setModelName("deepseek-chat");
        return new ConversationAwareAgentExecutor(memoryService, properties, streamingExecutor);
    }
}
