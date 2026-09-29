package com.xxx.insurance.ai.workflow.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.agent.AgentScopeStructuredOutput;
import com.xxx.insurance.ai.agent.ReactAgentStreamingExecutor;
import com.xxx.insurance.ai.workflow.execution.WorkflowPlanValidator;
import com.xxx.insurance.ai.workflow.model.*;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowPlannerAgentTests {

    @Test
    void convertsAndValidatesAgentScopePlannerOutput() {
        HarnessAgent reactAgent = mock(HarnessAgent.class);
        ReactAgentStreamingExecutor executor = mock(ReactAgentStreamingExecutor.class);
        when(executor.execute(eq(reactAgent), anyString(), isNull())).thenReturn(new AssistantMessage("""
                {"objective":"分析 PA-001 风险","tasks":[{"taskId":"task-1","sequence":1,
                "agentType":"product-analysis-agent","query":"分析 PA-001 风险","dependsOn":[],
                "maxRetries":1,"required":true}],"rationale":"单任务计划"}
                """));
        WorkflowPlannerAgent agent = new WorkflowPlannerAgent(
                reactAgent, new AgentScopeStructuredOutput(new ObjectMapper()),
                new WorkflowPlanValidator(), executor);
        AlignedWorkflowContext context = new AlignedWorkflowContext(
                "conversation-001", "分析 PA-001", ConversationTopicRelation.NO_HISTORY,
                "分析保险产品 PA-001 的风险", Map.of(), List.of(),
                new ProductRecallDecision(true, ProductRecallTrigger.FIRST_EXPLICIT_PRODUCT, "首次提及"),
                List.of(), false, 0, 0, 0, "trace-001", Instant.parse("2026-08-07T00:00:00Z"));
        IntentRoutingResult routing = new IntentRoutingResult(
                "PRODUCT_ANALYSIS", "product-analysis-agent", "产品分析");

        WorkflowPlan result = agent.plan(context, routing);

        assertThat(result.tasks()).singleElement().satisfies(task -> {
            assertThat(task.taskId()).isEqualTo("task-1");
            assertThat(task.agentName()).isEqualTo("product-analysis-agent");
        });
    }
}
