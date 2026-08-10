package com.xxx.insurance.ai.workflow.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.config.AiModelProperties;
import com.xxx.insurance.ai.workflow.config.WorkflowLifecycleProperties;
import com.xxx.insurance.ai.workflow.mapper.WorkflowExecutionMapper;
import com.xxx.insurance.ai.workflow.model.WorkflowInstanceExecutionView;
import com.xxx.insurance.ai.workflow.model.WorkflowResumeRequest;
import com.xxx.insurance.common.exception.BusinessException;
import com.xxx.insurance.product.model.ProductConfirmationRequest;
import com.xxx.insurance.product.service.ConversationConfirmedProductService;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalDbMainWorkflowServiceTests {

    @Test
    void rejectsResumeWhenAnotherRequestAlreadyClaimedRunningInstance() {
        WorkflowExecutionMapper executionMapper = mock(WorkflowExecutionMapper.class);
        CompiledGraph graph = mock(CompiledGraph.class);
        LocalDbMainWorkflowService service = service(executionMapper, graph);
        when(executionMapper.findInstance("wfi-001")).thenReturn(new WorkflowInstanceExecutionView(
                "wfi-001", "conversation-001", "RUNNING", Instant.parse("2026-08-10T00:00:00Z")));
        when(executionMapper.claimResume(any(), any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.resume(
                "wfi-001", new WorkflowResumeRequest("conversation-001")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not available for resume");

        verify(graph, never()).getState(any());
    }

    @Test
    void rejectsResumeForWaitingConfirmationInstanceBeforeClaiming() {
        WorkflowExecutionMapper executionMapper = mock(WorkflowExecutionMapper.class);
        LocalDbMainWorkflowService service = service(executionMapper, mock(CompiledGraph.class));
        when(executionMapper.findInstance("wfi-002")).thenReturn(new WorkflowInstanceExecutionView(
                "wfi-002", "conversation-002", "WAITING_CONFIRM", Instant.parse("2026-08-10T00:00:00Z")));

        assertThatThrownBy(() -> service.resume(
                "wfi-002", new WorkflowResumeRequest("conversation-002")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only RUNNING");

        verify(executionMapper, never()).claimResume(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsProductConfirmationWhenAnotherRequestAlreadyClaimedInstance() {
        WorkflowExecutionMapper executionMapper = mock(WorkflowExecutionMapper.class);
        CompiledGraph graph = mock(CompiledGraph.class);
        LocalDbMainWorkflowService service = service(executionMapper, graph);
        when(executionMapper.findInstance("wfi-003")).thenReturn(new WorkflowInstanceExecutionView(
                "wfi-003", "conversation-003", "WAITING_CONFIRM", Instant.parse("2026-08-10T00:00:00Z")));
        when(executionMapper.claimProductConfirmation(any(), any(), any(), any(), any())).thenReturn(0);

        ProductConfirmationRequest request = new ProductConfirmationRequest(
                "conversation-003", List.of("product-001"));

        assertThatThrownBy(() -> service.confirmProducts("wfi-003", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请勿重复提交");

        verify(executionMapper, never()).updateInstanceStatus(any(), any(), any(), any(), any());
    }

    private LocalDbMainWorkflowService service(WorkflowExecutionMapper executionMapper,
                                               CompiledGraph graph) {
        return new LocalDbMainWorkflowService(
                executionMapper,
                graph,
                new ObjectMapper().findAndRegisterModules(),
                mock(ConversationConfirmedProductService.class),
                new AiModelProperties(),
                mock(WorkflowEventPublisher.class),
                mock(WorkflowStartService.class),
                mock(WorkflowFinalizationService.class),
                new WorkflowLifecycleProperties(),
                mock(ThreadPoolTaskExecutor.class));
    }
}
