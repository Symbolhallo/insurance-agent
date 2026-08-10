package com.xxx.insurance.ai.workflow.service;

import com.xxx.insurance.ai.workflow.model.IntentRoutingResult;
import com.xxx.insurance.ai.workflow.model.IntentRoute;
import com.xxx.insurance.ai.workflow.model.WorkflowPlan;
import com.xxx.insurance.ai.workflow.model.WorkflowPlanTask;
import com.xxx.insurance.ai.workflow.node.IntentRecognitionNode;
import com.xxx.insurance.product.agent.ProductAnalysisAgent;
import com.xxx.insurance.knowledge.agent.KnowledgeQaAgent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowPlanValidatorTests {

    private final WorkflowPlanValidator validator = new WorkflowPlanValidator();

    private final IntentRoutingResult routingResult = new IntentRoutingResult(
            IntentRecognitionNode.PRODUCT_ANALYSIS_INTENT,
            ProductAnalysisAgent.AGENT_NAME,
            "test");

    @Test
    void acceptsSingleProductAnalysisTask() {
        WorkflowPlan plan = new WorkflowPlan(
                "分析指定保险产品",
                List.of(new WorkflowPlanTask(
                        "task-1",
                        1,
                        ProductAnalysisAgent.AGENT_NAME,
                        "分析鑫享人生的保障和风险",
                        List.of())),
                "产品分析意图由产品分析智能体处理");

        assertThat(validator.validate(plan, routingResult)).isSameAs(plan);
    }

    @Test
    void acceptsSingleKnowledgeQaTask() {
        IntentRoutingResult knowledgeRouting = new IntentRoutingResult(
                IntentRecognitionNode.KNOWLEDGE_QA_INTENT,
                KnowledgeQaAgent.AGENT_NAME,
                "通用保险知识问题");
        WorkflowPlan plan = new WorkflowPlan(
                "解释保险合同犹豫期",
                List.of(new WorkflowPlanTask(
                        "task-1",
                        1,
                        KnowledgeQaAgent.AGENT_NAME,
                        "解释保险合同犹豫期",
                        List.of())),
                "知识问答意图由知识问答智能体处理");

        assertThat(validator.validate(plan, knowledgeRouting)).isSameAs(plan);
    }

    @Test
    void rejectsUnregisteredTargetAgent() {
        WorkflowPlan plan = new WorkflowPlan(
                "查询客户资产",
                List.of(new WorkflowPlanTask(
                        "task-1",
                        1,
                        "asset-query-agent",
                        "查询客户资产",
                        List.of())),
                "资产查询");

        assertThatThrownBy(() -> validator.validate(plan, routingResult))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void acceptsTwoIndependentTasksForMixedIntent() {
        IntentRoutingResult mixedRouting = mixedRouting();
        WorkflowPlan plan = new WorkflowPlan(
                "解释犹豫期并分析产品",
                List.of(
                        new WorkflowPlanTask(
                                "task-1", 1, KnowledgeQaAgent.AGENT_NAME, "解释犹豫期", List.of()),
                        new WorkflowPlanTask(
                                "task-2", 2, ProductAnalysisAgent.AGENT_NAME, "分析 PA-001", List.of())),
                "两个任务没有依赖，可以并行");

        assertThat(validator.validate(plan, mixedRouting)).isSameAs(plan);
    }

    @Test
    void acceptsDependencyOnEarlierTask() {
        WorkflowPlan plan = new WorkflowPlan(
                "解释概念后分析产品",
                List.of(
                        new WorkflowPlanTask(
                                "task-1", 1, KnowledgeQaAgent.AGENT_NAME, "解释现金价值", List.of()),
                        new WorkflowPlanTask(
                                "task-2", 2, ProductAnalysisAgent.AGENT_NAME,
                                "结合现金价值概念分析 PA-001", List.of("task-1"))),
                "产品分析依赖知识解释");

        assertThat(validator.validate(plan, mixedRouting())).isSameAs(plan);
    }

    @Test
    void rejectsDependencyOnLaterTask() {
        WorkflowPlan plan = new WorkflowPlan(
                "非法计划",
                List.of(
                        new WorkflowPlanTask(
                                "task-1", 1, KnowledgeQaAgent.AGENT_NAME, "解释犹豫期", List.of("task-2")),
                        new WorkflowPlanTask(
                                "task-2", 2, ProductAnalysisAgent.AGENT_NAME, "分析 PA-001", List.of())),
                "逆序依赖");

        assertThatThrownBy(() -> validator.validate(plan, mixedRouting()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("earlier task");
    }

    @Test
    void rejectsTaskThatDoesNotMatchIntentRouteOrder() {
        WorkflowPlan plan = new WorkflowPlan(
                "错误的 Agent 顺序",
                List.of(
                        new WorkflowPlanTask(
                                "task-1", 1, ProductAnalysisAgent.AGENT_NAME, "分析 PA-001", List.of()),
                        new WorkflowPlanTask(
                                "task-2", 2, KnowledgeQaAgent.AGENT_NAME, "解释犹豫期", List.of())),
                "任务与输入意图顺序不一致");

        assertThatThrownBy(() -> validator.validate(plan, mixedRouting()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed or duplicated");
    }

    private IntentRoutingResult mixedRouting() {
        return new IntentRoutingResult(
                IntentRecognitionNode.MULTI_INTENT,
                null,
                "混合意图",
                List.of(
                        new IntentRoute(
                                IntentRecognitionNode.KNOWLEDGE_QA_INTENT,
                                KnowledgeQaAgent.AGENT_NAME,
                                "解释犹豫期",
                                "知识问答"),
                        new IntentRoute(
                                IntentRecognitionNode.PRODUCT_ANALYSIS_INTENT,
                                ProductAnalysisAgent.AGENT_NAME,
                                "分析 PA-001",
                                "产品分析")));
    }
}
