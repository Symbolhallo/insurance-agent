package com.xxx.insurance.ai.workflow.execution;

import com.xxx.insurance.ai.workflow.sse.service.NoOpWorkflowEventPublisher;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.serializer.StateSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.workflow.checkpoint.config.GraphCheckpointConfig;
import com.xxx.insurance.ai.workflow.config.WorkflowExecutionConfig;
import com.xxx.insurance.ai.workflow.config.WorkflowLifecycleProperties;
import com.xxx.insurance.ai.workflow.config.WorkflowTaskGraphConfig;
import com.xxx.insurance.ai.workflow.model.AgentTaskExecutionResult;
import com.xxx.insurance.ai.workflow.model.AgentTaskStatus;
import com.xxx.insurance.ai.workflow.model.SubAgentExecutionResult;
import com.xxx.insurance.ai.workflow.model.WorkflowAgentTaskContext;
import com.xxx.insurance.ai.workflow.model.WorkflowPlanTask;
import com.xxx.insurance.ai.workflow.node.AgentInvokeNode;
import com.xxx.insurance.product.agent.ProductAnalysisAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowTaskGraphRunnerTests {

    private WorkflowSubAgentRouter router;

    private ThreadPoolTaskExecutor executor;

    private WorkflowTaskGraphRunner runner;

    @BeforeEach
    void setUp() throws Exception {
        router = mock(WorkflowSubAgentRouter.class);
        executor = new WorkflowExecutionConfig().workflowDagTaskExecutor();
        AgentInvokeNode invokeNode = new AgentInvokeNode(router, new NoOpWorkflowEventPublisher());
        StateSerializer serializer = new GraphCheckpointConfig().mainWorkflowStateSerializer(
                new ObjectMapper().findAndRegisterModules());
        BaseCheckpointSaver saver = MemorySaver.builder().build();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory(Map.of("saver", saver));
        CompiledGraph graph = new WorkflowTaskGraphConfig().workflowTaskGraph(
                invokeNode, serializer, beanFactory.getBeanProvider(BaseCheckpointSaver.class));
        runner = new WorkflowTaskGraphRunner(graph, executor, new WorkflowLifecycleProperties());
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void recoveredSuccessfulTaskIsNotInvokedAgain() {
        WorkflowAgentTaskContext context = context();
        when(router.invoke(context)).thenReturn(response());

        AgentTaskExecutionResult first = runner.execute(context);
        AgentTaskExecutionResult recovered = runner.execute(context);

        assertThat(first.status()).isEqualTo(AgentTaskStatus.SUCCESS);
        assertThat(recovered).isEqualTo(first);
        verify(router, times(1)).invoke(context);
    }

    private WorkflowAgentTaskContext context() {
        return new WorkflowAgentTaskContext(
                new WorkflowPlanTask("A", 1, ProductAnalysisAgent.AGENT_NAME,
                        "query", List.of(), 1, true),
                "conversation-001", "workflow-restore-001", "step-001", "original",
                List.of(), List.of(), false);
    }

    private SubAgentExecutionResult response() {
        Instant now = Instant.now();
        return new SubAgentExecutionResult(
                ProductAnalysisAgent.AGENT_NAME, "conversation-001", "inv-001", "answer",
                true, 1, now, 6, false, 0);
    }
}
