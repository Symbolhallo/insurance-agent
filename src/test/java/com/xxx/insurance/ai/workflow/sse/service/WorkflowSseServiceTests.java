package com.xxx.insurance.ai.workflow.sse.service;

import com.xxx.insurance.ai.workflow.model.MainWorkflowRequest;
import com.xxx.insurance.ai.workflow.service.MainWorkflowService;
import com.xxx.insurance.product.model.ProductConfirmationRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowSseServiceTests {

    @Test
    void subscribesBeforeStartingGraphWithTokenStreamingEnabled() {
        MainWorkflowService mainWorkflowService = mock(MainWorkflowService.class);
        LocalDbWorkflowSseEventService eventService = mock(LocalDbWorkflowSseEventService.class);
        ThreadPoolTaskExecutor taskExecutor = mock(ThreadPoolTaskExecutor.class);
        SseEmitter emitter = new SseEmitter();
        MainWorkflowRequest request = new MainWorkflowRequest(
                "分析鑫享人生", "conversation-001", "request-001");
        when(mainWorkflowService.createWorkflowInstanceId()).thenReturn("wfi-001");
        when(eventService.subscribeNewRun("wfi-001")).thenReturn(emitter);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);

        SseEmitter result = new WorkflowSseService(mainWorkflowService, eventService, taskExecutor)
                .start(request);

        assertThat(result).isSameAs(emitter);
        var ordered = inOrder(mainWorkflowService, eventService, taskExecutor);
        ordered.verify(mainWorkflowService).createWorkflowInstanceId();
        ordered.verify(eventService).subscribeNewRun("wfi-001");
        ordered.verify(taskExecutor).execute(task.capture());
        task.getValue().run();
        verify(mainWorkflowService).run("wfi-001", request, true);
    }

    @Test
    void subscribesBeforeResumingConfirmationWithTokenStreamingEnabled() {
        MainWorkflowService mainWorkflowService = mock(MainWorkflowService.class);
        LocalDbWorkflowSseEventService eventService = mock(LocalDbWorkflowSseEventService.class);
        ThreadPoolTaskExecutor taskExecutor = mock(ThreadPoolTaskExecutor.class);
        SseEmitter emitter = new SseEmitter();
        when(eventService.subscribeConfirmationResume("wfi-001", "wfi-001:5"))
                .thenReturn(emitter);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        ProductConfirmationRequest request = new ProductConfirmationRequest(
                "conversation-001", List.of("PA-001"));
        when(mainWorkflowService.claimProductConfirmation("wfi-001", "conversation-001"))
                .thenReturn(9L);

        SseEmitter result = new WorkflowSseService(mainWorkflowService, eventService, taskExecutor)
                .confirmProducts("wfi-001", request, "wfi-001:5");

        assertThat(result).isSameAs(emitter);
        var ordered = inOrder(mainWorkflowService, eventService, taskExecutor);
        ordered.verify(mainWorkflowService).claimProductConfirmation("wfi-001", "conversation-001");
        ordered.verify(eventService).subscribeConfirmationResume("wfi-001", "wfi-001:5");
        verify(taskExecutor).execute(task.capture());
        task.getValue().run();
        verify(mainWorkflowService).confirmClaimedProducts("wfi-001", request, true, 9L);
    }
}
