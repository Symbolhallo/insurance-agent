package com.xxx.insurance.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.workflow.model.WorkflowPlan;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopeStructuredOutputTests {

    private final AgentScopeStructuredOutput structuredOutput =
            new AgentScopeStructuredOutput(new ObjectMapper());

    @Test
    void convertsDirectBusinessPayload() {
        WorkflowPlan plan = structuredOutput.convert("""
                {"objective":"解释等待期","tasks":[],"rationale":"单一目标"}
                """, WorkflowPlan.class);

        assertThat(plan.objective()).isEqualTo("解释等待期");
        assertThat(plan.rationale()).isEqualTo("单一目标");
    }

    @Test
    void unwrapsSchemaEnvelopeProducedByCompatibleModel() {
        WorkflowPlan plan = structuredOutput.convert("""
                {"type":"object","properties":{
                  "objective":"解释等待期",
                  "tasks":[{"taskId":"task-1","sequence":1,"agentType":"knowledge-qa-agent",
                    "query":"解释保险等待期","dependsOn":[],"maxRetries":1,"required":true}],
                  "rationale":"单任务计划"
                }}
                """, WorkflowPlan.class);

        assertThat(plan.objective()).isEqualTo("解释等待期");
        assertThat(plan.tasks()).singleElement().satisfies(task ->
                assertThat(task.agentType()).isEqualTo("knowledge-qa-agent"));
    }
}
