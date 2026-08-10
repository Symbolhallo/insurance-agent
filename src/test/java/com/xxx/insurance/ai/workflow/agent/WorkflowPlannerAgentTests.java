package com.xxx.insurance.ai.workflow.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.xxx.insurance.ai.agent.ReactAgentStreamingExecutor;
import com.xxx.insurance.ai.workflow.config.WorkflowPlannerAgentConfig;
import com.xxx.insurance.ai.workflow.model.AlignedWorkflowContext;
import com.xxx.insurance.ai.workflow.model.ConversationTopicRelation;
import com.xxx.insurance.ai.workflow.model.IntentRoutingResult;
import com.xxx.insurance.ai.workflow.model.ProductRecallDecision;
import com.xxx.insurance.ai.workflow.model.ProductRecallTrigger;
import com.xxx.insurance.ai.workflow.model.WorkflowPlan;
import com.xxx.insurance.ai.workflow.service.WorkflowPlanValidator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowPlannerAgentTests {

    @Test
    void rendersJsonSchemaWithoutTreatingItAsTemplateSyntax() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                AssistantMessage.builder()
                        .content("""
                                {
                                  "objective": "分析 PA-001 风险",
                                  "tasks": [
                                    {
                                      "taskId": "task-1",
                                      "sequence": 1,
                                      "agentType": "product-analysis-agent",
                                      "query": "分析 PA-001 风险",
                                      "dependsOn": [],
                                      "maxRetries": 1,
                                      "required": true
                                    }
                                  ],
                                  "rationale": "根据产品分析意图生成单任务计划"
                                }
                                """)
                        .build()))));
        WorkflowPlannerAgentConfig config = new WorkflowPlannerAgentConfig();
        BeanOutputConverter<WorkflowPlan> outputConverter = config.workflowPlannerOutputConverter();
        ReactAgent reactAgent = config.workflowPlannerReactAgent(chatModel);
        WorkflowPlannerAgent plannerAgent = config.workflowPlannerAgent(
                reactAgent,
                outputConverter,
                new WorkflowPlanValidator(),
                mock(ReactAgentStreamingExecutor.class));
        AlignedWorkflowContext context = new AlignedWorkflowContext(
                "conversation-001",
                "分析 PA-001",
                ConversationTopicRelation.NO_HISTORY,
                "分析保险产品 PA-001 的风险",
                Map.of(),
                List.of(),
                new ProductRecallDecision(
                        true,
                        ProductRecallTrigger.FIRST_EXPLICIT_PRODUCT,
                        "首次明确提及产品"),
                List.of(),
                false,
                0,
                0,
                0,
                "trace-001",
                Instant.parse("2026-08-07T00:00:00Z"));
        IntentRoutingResult routingResult = new IntentRoutingResult(
                "PRODUCT_ANALYSIS",
                "product-analysis-agent",
                "当前工作流仅开放产品分析智能体");

        WorkflowPlan result = plannerAgent.plan(context, routingResult);

        assertThat(result.objective()).isEqualTo("分析 PA-001 风险");
        assertThat(result.tasks()).singleElement().satisfies(task -> {
            assertThat(task.taskId()).isEqualTo("task-1");
            assertThat(task.agentName()).isEqualTo("product-analysis-agent");
        });
    }
}
