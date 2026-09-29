package com.xxx.insurance.ai.workflow.controller;

import com.xxx.insurance.ai.workflow.model.MainWorkflowRequest;
import com.xxx.insurance.ai.workflow.sse.model.WorkflowSseSubscription;
import com.xxx.insurance.ai.workflow.sse.service.WorkflowSseService;
import com.xxx.insurance.common.security.RequestIdentity;
import com.xxx.insurance.common.security.ResourceAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MainWorkflowSseControllerTests {

    @Test
    void returnsPreallocatedWorkflowInstanceIdBeforeStreamingEvents() {
        WorkflowSseService workflowSseService = mock(WorkflowSseService.class);
        MainWorkflowRequest request = new MainWorkflowRequest(
                "分析鑫享人生", "conversation-001", "request-001");
        RequestIdentity identity = RequestIdentity.localDefault();
        SseEmitter emitter = new SseEmitter();
        when(workflowSseService.start(request.withIdentity(identity)))
                .thenReturn(new WorkflowSseSubscription("wfi-001", emitter));

        var response = new MainWorkflowSseController(
                workflowSseService, mock(ResourceAccessService.class)).streamRun(request, identity);

        assertThat(response.getHeaders().getFirst(
                MainWorkflowSseController.WORKFLOW_INSTANCE_ID_HEADER)).isEqualTo("wfi-001");
        assertThat(response.getBody()).isSameAs(emitter);
    }
}
