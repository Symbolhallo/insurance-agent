package com.xxx.insurance.ai.workflow.execution;

import com.xxx.insurance.ai.agent.AgentExecutionContext;
import com.xxx.insurance.ai.workflow.model.AgentTaskExecutionResult;
import com.xxx.insurance.ai.workflow.model.AgentTaskStatus;
import com.xxx.insurance.ai.workflow.model.SubAgentExecutionResult;
import com.xxx.insurance.ai.workflow.model.WorkflowAgentTaskContext;
import com.xxx.insurance.ai.workflow.model.WorkflowPlanTask;
import com.xxx.insurance.asset.agent.AssetQueryAgent;
import com.xxx.insurance.knowledge.agent.KnowledgeQaAgent;
import com.xxx.insurance.knowledge.model.KnowledgeQaChatRequest;
import com.xxx.insurance.knowledge.model.KnowledgeQaChatResponse;
import com.xxx.insurance.policy.agent.PolicyQueryAgent;
import com.xxx.insurance.product.agent.ProductAnalysisAgent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowSubAgentRouterTests {

    @Test
    void boundsCombinedDependencyAnswersAndRetainsEachDependencyId() {
        KnowledgeQaAgent knowledgeQaAgent = mock(KnowledgeQaAgent.class);
        WorkflowSubAgentRouter router = new WorkflowSubAgentRouter(
                mock(ProductAnalysisAgent.class), knowledgeQaAgent,
                mock(PolicyQueryAgent.class), mock(AssetQueryAgent.class));
        when(knowledgeQaAgent.chat(any(KnowledgeQaChatRequest.class), any(AgentExecutionContext.class)))
                .thenReturn(knowledgeResponse());
        WorkflowAgentTaskContext context = contextWithLongDependencyAnswers();

        router.invoke(context);

        ArgumentCaptor<KnowledgeQaChatRequest> requestCaptor =
                ArgumentCaptor.forClass(KnowledgeQaChatRequest.class);
        verify(knowledgeQaAgent).chat(requestCaptor.capture(), any(AgentExecutionContext.class));
        String agentQuery = requestCaptor.getValue().message();
        assertThat(agentQuery).hasSizeLessThanOrEqualTo(2000);
        assertThat(agentQuery).contains("taskId=task-policy", "taskId=task-asset", "...[truncated]");
    }

    private WorkflowAgentTaskContext contextWithLongDependencyAnswers() {
        WorkflowPlanTask task = new WorkflowPlanTask(
                "task-knowledge", 3, KnowledgeQaAgent.AGENT_NAME,
                "结合保单和资产结果解释保险概念", List.of("task-policy", "task-asset"), 1, true);
        return new WorkflowAgentTaskContext(
                task, "conversation-001", "workflow-001", "step-001", "original",
                List.of(), List.of(
                        dependency("task-policy", "policy ".repeat(500)),
                        dependency("task-asset", "asset ".repeat(500))), false);
    }

    private AgentTaskExecutionResult dependency(String taskId, String answer) {
        Instant now = Instant.now();
        SubAgentExecutionResult response = new SubAgentExecutionResult(
                "upstream-agent", "conversation-001", "inv-" + taskId, answer,
                true, 1, now, answer.length(), false, 0);
        return new AgentTaskExecutionResult(
                taskId, 1, "upstream-agent", AgentTaskStatus.SUCCESS,
                response, null, null, now, now, 1, 1);
    }

    private KnowledgeQaChatResponse knowledgeResponse() {
        return new KnowledgeQaChatResponse(
                KnowledgeQaAgent.AGENT_NAME, "conversation-001", "inv-knowledge", "answer",
                true, 1, Instant.now(), 6, false, 0);
    }
}
