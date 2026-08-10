package com.xxx.insurance.ai.workflow.service;

import com.xxx.insurance.ai.workflow.model.IntentRoute;
import com.xxx.insurance.ai.workflow.model.IntentRoutingResult;
import com.xxx.insurance.ai.workflow.model.WorkflowPlan;
import com.xxx.insurance.ai.workflow.model.WorkflowPlanTask;
import com.xxx.insurance.ai.workflow.node.IntentRecognitionNode;
import com.xxx.insurance.knowledge.agent.KnowledgeQaAgent;
import com.xxx.insurance.product.agent.ProductAnalysisAgent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowPlanValidatorTests {

    private final WorkflowPlanValidator validator = new WorkflowPlanValidator();

    @Test
    void acceptsArbitraryAcyclicDependenciesAndRepeatedAgent() {
        WorkflowPlan plan = new WorkflowPlan(
                "dynamic DAG",
                List.of(
                        task("A", 1, ProductAnalysisAgent.AGENT_NAME, "C"),
                        task("B", 2, KnowledgeQaAgent.AGENT_NAME),
                        task("C", 3, ProductAnalysisAgent.AGENT_NAME, "B")),
                "sequence only controls display order");

        assertThat(validator.validate(plan, mixedRouting())).isSameAs(plan);
    }

    @Test
    void rejectsDuplicateTaskId() {
        WorkflowPlan plan = plan(
                task("A", 1, ProductAnalysisAgent.AGENT_NAME),
                task("A", 2, KnowledgeQaAgent.AGENT_NAME));

        assertThatThrownBy(() -> validator.validate(plan, mixedRouting()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
    }

    @Test
    void rejectsAgentOutsideIntentWhitelist() {
        WorkflowPlan plan = plan(task("A", 1, "unknown-agent"));

        assertThatThrownBy(() -> validator.validate(plan, mixedRouting()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentType");
    }

    @Test
    void rejectsBlankQuery() {
        WorkflowPlan plan = plan(new WorkflowPlanTask(
                "A", 1, ProductAnalysisAgent.AGENT_NAME, " ", List.of(), 1, true));

        assertThatThrownBy(() -> validator.validate(plan, mixedRouting()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
    }

    @Test
    void rejectsMissingDependency() {
        WorkflowPlan plan = plan(task("A", 1, ProductAnalysisAgent.AGENT_NAME, "missing"));

        assertThatThrownBy(() -> validator.validate(plan, mixedRouting()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void rejectsSelfDependency() {
        WorkflowPlan plan = plan(task("A", 1, ProductAnalysisAgent.AGENT_NAME, "A"));

        assertThatThrownBy(() -> validator.validate(plan, mixedRouting()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("itself");
    }

    @Test
    void rejectsCycle() {
        WorkflowPlan plan = plan(
                task("A", 1, ProductAnalysisAgent.AGENT_NAME, "C"),
                task("B", 2, KnowledgeQaAgent.AGENT_NAME, "A"),
                task("C", 3, ProductAnalysisAgent.AGENT_NAME, "B"));

        assertThatThrownBy(() -> validator.validate(plan, mixedRouting()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void rejectsRetryCountOutsideBound() {
        WorkflowPlan plan = plan(new WorkflowPlanTask(
                "A", 1, ProductAnalysisAgent.AGENT_NAME, "query", List.of(), 4, true));

        assertThatThrownBy(() -> validator.validate(plan, mixedRouting()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRetries");
    }

    private WorkflowPlan plan(WorkflowPlanTask... tasks) {
        return new WorkflowPlan("test", List.of(tasks), "test");
    }

    private WorkflowPlanTask task(String id, int sequence, String agent, String... dependencies) {
        return new WorkflowPlanTask(id, sequence, agent, "query-" + id,
                List.of(dependencies), 1, true);
    }

    private IntentRoutingResult mixedRouting() {
        return new IntentRoutingResult(
                IntentRecognitionNode.MULTI_INTENT,
                null,
                "mixed",
                List.of(
                        new IntentRoute(IntentRecognitionNode.PRODUCT_ANALYSIS_INTENT,
                                ProductAnalysisAgent.AGENT_NAME, "product", "test"),
                        new IntentRoute(IntentRecognitionNode.KNOWLEDGE_QA_INTENT,
                                KnowledgeQaAgent.AGENT_NAME, "knowledge", "test")));
    }
}
