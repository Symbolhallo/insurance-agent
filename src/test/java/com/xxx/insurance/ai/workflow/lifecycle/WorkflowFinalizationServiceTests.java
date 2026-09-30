package com.xxx.insurance.ai.workflow.lifecycle;

import com.xxx.insurance.ai.workflow.sse.service.LocalDbWorkflowSseEventService;
import com.xxx.insurance.ai.memory.model.AgentInvocationRecord;
import com.xxx.insurance.ai.memory.model.AgentMemoryExchange;
import com.xxx.insurance.ai.memory.service.AgentMemoryService;
import com.xxx.insurance.ai.workflow.checkpoint.OceanBaseCheckpointSaver;
import com.xxx.insurance.ai.workflow.config.WorkflowLifecycleProperties;
import com.xxx.insurance.ai.workflow.mapper.WorkflowExecutionMapper;
import com.xxx.insurance.ai.workflow.model.MainWorkflowResponse;
import com.xxx.insurance.ai.workflow.sse.model.WorkflowSseEventType;
import com.xxx.insurance.common.security.RequestIdentity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowFinalizationServiceTests {

    @Test
    void usesWorkflowInstanceIdAsFinalMemoryIdempotencyKey() {
        WorkflowExecutionMapper mapper = mock(WorkflowExecutionMapper.class);
        AgentMemoryService memoryService = mock(AgentMemoryService.class);
        when(mapper.finalizeInstance(
                anyString(), anyString(), anyString(), any(), anyString(), any(Long.class), any())).thenReturn(1);
        when(memoryService.isEnabled()).thenReturn(true);
        WorkflowFinalizationService service = new WorkflowFinalizationService(
                mapper,
                memoryService,
                mock(OceanBaseCheckpointSaver.class),
                mock(LocalDbWorkflowSseEventService.class),
                new WorkflowLifecycleProperties());

        boolean applied = service.complete(response(), "{}", "deepseek-chat", 7L);

        ArgumentCaptor<AgentInvocationRecord> invocation = ArgumentCaptor.forClass(AgentInvocationRecord.class);
        verify(memoryService).saveSuccessfulExchange(any(), invocation.capture());
        assertThat(applied).isTrue();
        assertThat(invocation.getValue().invocationId()).isEqualTo("wfa-wfi-001");
        verify(mapper).deleteConversationLock("wfi-001");
    }

    @Test
    void lateFailureDoesNotMutateAlreadyTerminalWorkflow() {
        WorkflowExecutionMapper mapper = mock(WorkflowExecutionMapper.class);
        when(mapper.failInstanceIfNonTerminal(
                anyString(), anyString(), anyString(), any(Long.class), any())).thenReturn(0);
        OceanBaseCheckpointSaver checkpointSaver = mock(OceanBaseCheckpointSaver.class);
        LocalDbWorkflowSseEventService eventService = mock(LocalDbWorkflowSseEventService.class);
        WorkflowFinalizationService service = new WorkflowFinalizationService(
                mapper, mock(AgentMemoryService.class), checkpointSaver, eventService,
                new WorkflowLifecycleProperties());

        boolean applied = service.fail("wfi-001", "conversation-001", "late error", 7L, Instant.now());

        assertThat(applied).isFalse();
        verify(checkpointSaver, never()).markWorkflowFailed(anyString(), any(Long.class));
        verify(eventService, never()).persistTransactionalEvent(
                anyString(), anyString(), any(Long.class), any(), any(), any());
    }

    @Test
    void publishesBusinessBoundaryMessageForUnsupportedIntent() {
        WorkflowExecutionMapper mapper = mock(WorkflowExecutionMapper.class);
        when(mapper.failInstanceIfNonTerminal(
                anyString(), anyString(), anyString(), any(Long.class), any())).thenReturn(1);
        AgentMemoryService memoryService = mock(AgentMemoryService.class);
        when(memoryService.isEnabled()).thenReturn(true);
        LocalDbWorkflowSseEventService eventService = mock(LocalDbWorkflowSseEventService.class);
        WorkflowFinalizationService service = new WorkflowFinalizationService(
                mapper, memoryService, mock(OceanBaseCheckpointSaver.class), eventService,
                new WorkflowLifecycleProperties());
        String frontendMessage = "其他功能暂不支持";

        boolean applied = service.failUnsupported(
                "wfi-001", "conversation-001", "internal error", frontendMessage,
                "帮我订一张机票", "qwen-plus", RequestIdentity.localDefault(),
                7L, Instant.now());

        assertThat(applied).isTrue();
        ArgumentCaptor<AgentMemoryExchange> exchange = ArgumentCaptor.forClass(AgentMemoryExchange.class);
        ArgumentCaptor<AgentInvocationRecord> invocation = ArgumentCaptor.forClass(AgentInvocationRecord.class);
        verify(memoryService).saveSuccessfulExchange(exchange.capture(), invocation.capture());
        assertThat(exchange.getValue().userMessage().getTextContent()).isEqualTo("帮我订一张机票");
        assertThat(exchange.getValue().assistantMessage().getTextContent()).isEqualTo(frontendMessage);
        assertThat(invocation.getValue().invocationId()).isEqualTo("wfu-wfi-001");
        assertThat(invocation.getValue().status()).isEqualTo("UNSUPPORTED");
        assertThat(invocation.getValue().errorCode()).isEqualTo("UNSUPPORTED_INTENT");
        verify(eventService).persistTransactionalEvent(
                eq("wfi-001"), eq("conversation-001"), eq(7L),
                eq(WorkflowSseEventType.ERROR), eq(null),
                eq(Map.of("status", "UNSUPPORTED", "message", frontendMessage)));
    }

    @Test
    void doesNotPersistUnsupportedConversationWhenTerminalStateWasAlreadyClaimed() {
        WorkflowExecutionMapper mapper = mock(WorkflowExecutionMapper.class);
        when(mapper.failInstanceIfNonTerminal(
                anyString(), anyString(), anyString(), any(Long.class), any())).thenReturn(0);
        AgentMemoryService memoryService = mock(AgentMemoryService.class);
        WorkflowFinalizationService service = new WorkflowFinalizationService(
                mapper, memoryService, mock(OceanBaseCheckpointSaver.class),
                mock(LocalDbWorkflowSseEventService.class), new WorkflowLifecycleProperties());

        boolean applied = service.failUnsupported(
                "wfi-001", "conversation-001", "internal error", "其他功能暂不支持",
                "帮我订一张机票", "qwen-plus", RequestIdentity.localDefault(),
                7L, Instant.now());

        assertThat(applied).isFalse();
        verify(memoryService, never()).saveSuccessfulExchange(any(), any());
    }

    private MainWorkflowResponse response() {
        Instant startedAt = Instant.parse("2026-08-10T00:00:00Z");
        Instant endedAt = startedAt.plusSeconds(2);
        return new MainWorkflowResponse(
                true, "main-workflow-v1", "wfi-001", null, Map.of("summary", "step-001"),
                "conversation-001", "分析产品", null, "分析产品", Map.of(), "PRODUCT_ANALYSIS",
                null, null, false, List.of(), null, "SUCCESS", "分析结果", null, null, null,
                null, 2000, startedAt, endedAt, null);
    }
}
