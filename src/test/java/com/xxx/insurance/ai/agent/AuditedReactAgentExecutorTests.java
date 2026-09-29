package com.xxx.insurance.ai.agent;

import io.agentscope.core.message.AssistantMessage;
import io.agentscope.harness.agent.HarnessAgent;
import com.xxx.insurance.ai.config.AiModelProperties;
import com.xxx.insurance.ai.memory.model.AgentInvocationRecord;
import com.xxx.insurance.ai.memory.service.AgentMemoryService;
import com.xxx.insurance.ai.workflow.model.SubAgentExecutionResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditedReactAgentExecutorTests {

    @Test
    void invokesRealReactAgentAndSavesSuccessfulAudit() throws Exception {
        HarnessAgent reactAgent = mock(HarnessAgent.class);
        AgentMemoryService memoryService = mock(AgentMemoryService.class);
        when(memoryService.isEnabled()).thenReturn(true);
        ReactAgentStreamingExecutor streamingExecutor = mock(ReactAgentStreamingExecutor.class);
        when(streamingExecutor.execute(reactAgent, "查询有效保单", null))
                .thenReturn(new AssistantMessage("基于保单Tool生成的回答"));
        AuditedReactAgentExecutor executor = new AuditedReactAgentExecutor(
                memoryService, modelProperties(), streamingExecutor);

        SubAgentExecutionResult result = executor.execute(
                reactAgent,
                "policy-query-agent",
                "pqa-",
                "查询有效保单",
                "conversation-001",
                new AgentExecutionContext("wfi-001", "step-001", "查询我的保单", false, "task-1", false));

        assertThat(result.modelInvoked()).isTrue();
        assertThat(result.answer()).isEqualTo("基于保单Tool生成的回答");
        assertThat(result.invocationId()).startsWith("pqa-");
        ArgumentCaptor<AgentInvocationRecord> invocation = ArgumentCaptor.forClass(AgentInvocationRecord.class);
        verify(memoryService).saveSuccessfulInvocation(invocation.capture());
        assertThat(invocation.getValue().workflowInstanceId()).isEqualTo("wfi-001");
        assertThat(invocation.getValue().customerId()).isEqualTo("MOCK-CUSTOMER-001");
        assertThat(invocation.getValue().status()).isEqualTo("SUCCESS");
    }

    @Test
    void usesStreamingExecutorWithTaskIdentityForSseRun() throws Exception {
        HarnessAgent reactAgent = mock(HarnessAgent.class);
        ReactAgentStreamingExecutor streamingExecutor = mock(ReactAgentStreamingExecutor.class);
        when(streamingExecutor.execute(
                eq(reactAgent), eq("查询资产余额"), any(AgentTokenStreamContext.class)))
                .thenReturn(new AssistantMessage("基于资产Tool生成的回答"));
        AuditedReactAgentExecutor executor = new AuditedReactAgentExecutor(
                mock(AgentMemoryService.class), modelProperties(), streamingExecutor);

        SubAgentExecutionResult result = executor.execute(
                reactAgent,
                "asset-query-agent",
                "aqa-",
                "查询资产余额",
                "conversation-001",
                new AgentExecutionContext("wfi-001", "step-001", "查询我的资产", false, "task-2", true));

        assertThat(result.modelInvoked()).isTrue();
        verify(streamingExecutor).execute(
                eq(reactAgent), eq("查询资产余额"), org.mockito.ArgumentMatchers.argThat(context ->
                        "wfi-001".equals(context.workflowInstanceId())
                                && "task-2".equals(context.taskId())
                                && "asset-query-agent".equals(context.agentName())
                                && "SUB_AGENT".equals(context.phase())));
    }

    private AiModelProperties modelProperties() {
        AiModelProperties properties = new AiModelProperties();
        properties.setModelName("deepseek-chat");
        return properties;
    }
}
