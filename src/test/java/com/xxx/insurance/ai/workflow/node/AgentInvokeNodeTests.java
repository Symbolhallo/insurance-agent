package com.xxx.insurance.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.xxx.insurance.ai.workflow.model.AgentTaskExecutionResult;
import com.xxx.insurance.ai.workflow.model.AgentTaskStatus;
import com.xxx.insurance.ai.workflow.model.SubAgentExecutionResult;
import com.xxx.insurance.ai.workflow.model.WorkflowAgentTaskContext;
import com.xxx.insurance.ai.workflow.model.WorkflowPlanTask;
import com.xxx.insurance.ai.workflow.model.WorkflowTaskStateKeys;
import com.xxx.insurance.ai.workflow.sse.service.NoOpWorkflowEventPublisher;
import com.xxx.insurance.ai.workflow.execution.WorkflowSubAgentRouter;
import com.xxx.insurance.product.agent.ProductAnalysisAgent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentInvokeNodeTests {

    @Test
    void retriesTransientFailureAndThenSucceeds() {
        WorkflowSubAgentRouter router = mock(WorkflowSubAgentRouter.class);
        WorkflowAgentTaskContext context = context(2);
        when(router.invoke(context))
                .thenThrow(new IllegalStateException("temporary"))
                .thenReturn(response());
        AgentInvokeNode node = new AgentInvokeNode(router, new NoOpWorkflowEventPublisher());

        AgentTaskExecutionResult result = result(node, context);

        assertThat(result.status()).isEqualTo(AgentTaskStatus.SUCCESS);
        assertThat(result.attempts()).isEqualTo(2);
    }

    @Test
    void returnsFailedAfterRetryBudgetIsExhausted() {
        WorkflowSubAgentRouter router = mock(WorkflowSubAgentRouter.class);
        WorkflowAgentTaskContext context = context(1);
        when(router.invoke(context)).thenThrow(new IllegalStateException("unavailable"));
        AgentInvokeNode node = new AgentInvokeNode(router, new NoOpWorkflowEventPublisher());

        AgentTaskExecutionResult result = result(node, context);

        assertThat(result.status()).isEqualTo(AgentTaskStatus.FAILED);
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(result.errorMessage()).contains("unavailable");
    }

    @Test
    void doesNotRetryDeterministicRequestValidationFailure() {
        WorkflowSubAgentRouter router = mock(WorkflowSubAgentRouter.class);
        WorkflowAgentTaskContext context = context(3);
        when(router.invoke(context)).thenThrow(new IllegalArgumentException("message is too long"));
        AgentInvokeNode node = new AgentInvokeNode(router, new NoOpWorkflowEventPublisher());

        AgentTaskExecutionResult result = result(node, context);

        assertThat(result.status()).isEqualTo(AgentTaskStatus.FAILED);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.errorMessage()).contains("message is too long");
        verify(router, times(1)).invoke(context);
    }

    private AgentTaskExecutionResult result(AgentInvokeNode node, WorkflowAgentTaskContext context) {
        AgentTaskExecutionResult running = new AgentTaskExecutionResult(
                context.task().taskId(), 1, context.task().agentType(), AgentTaskStatus.RUNNING,
                null, null, null, Instant.now(), null, 0, 0);
        OverAllState state = new OverAllState(Map.of(WorkflowTaskStateKeys.TASK_RESULT, running));
        RunnableConfig config = RunnableConfig.builder()
                .addMetadata(AgentInvokeNode.TASK_CONTEXT_METADATA, context)
                .build();
        return (AgentTaskExecutionResult) node.apply(state, config).join()
                .get(WorkflowTaskStateKeys.TASK_RESULT);
    }

    private WorkflowAgentTaskContext context(int maxRetries) {
        return new WorkflowAgentTaskContext(
                new WorkflowPlanTask("A", 1, ProductAnalysisAgent.AGENT_NAME,
                        "query", List.of(), maxRetries, true),
                "conversation-001", "workflow-001", "step-001", "original",
                List.of(), List.of(), false);
    }

    private SubAgentExecutionResult response() {
        Instant now = Instant.now();
        return new SubAgentExecutionResult(
                ProductAnalysisAgent.AGENT_NAME, "conversation-001", "inv-001", "answer",
                true, 1, now, 6, false, 0);
    }
}
