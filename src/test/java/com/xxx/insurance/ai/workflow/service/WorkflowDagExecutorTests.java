package com.xxx.insurance.ai.workflow.service;

import com.xxx.insurance.ai.agent.AgentExecutionContext;
import com.xxx.insurance.ai.workflow.config.WorkflowExecutionConfig;
import com.xxx.insurance.ai.workflow.model.AgentTaskStatus;
import com.xxx.insurance.ai.workflow.model.AlignedWorkflowContext;
import com.xxx.insurance.ai.workflow.model.ConversationTopicRelation;
import com.xxx.insurance.ai.workflow.model.DagExecutionResult;
import com.xxx.insurance.ai.workflow.model.IntentRoute;
import com.xxx.insurance.ai.workflow.model.IntentRoutingResult;
import com.xxx.insurance.ai.workflow.model.ProductRecallDecision;
import com.xxx.insurance.ai.workflow.model.ProductRecallTrigger;
import com.xxx.insurance.ai.workflow.model.WorkflowPlan;
import com.xxx.insurance.ai.workflow.model.WorkflowPlanTask;
import com.xxx.insurance.ai.workflow.node.IntentRecognitionNode;
import com.xxx.insurance.knowledge.agent.KnowledgeQaAgent;
import com.xxx.insurance.knowledge.model.KnowledgeQaChatRequest;
import com.xxx.insurance.knowledge.model.KnowledgeQaChatResponse;
import com.xxx.insurance.product.agent.ProductAnalysisAgent;
import com.xxx.insurance.product.model.ProductAnalysisChatRequest;
import com.xxx.insurance.product.model.ProductAnalysisChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowDagExecutorTests {

    private ProductAnalysisAgent productAnalysisAgent;

    private KnowledgeQaAgent knowledgeQaAgent;

    private ThreadPoolTaskExecutor taskExecutor;

    private WorkflowDagExecutor executor;

    @BeforeEach
    void setUp() {
        productAnalysisAgent = mock(ProductAnalysisAgent.class);
        knowledgeQaAgent = mock(KnowledgeQaAgent.class);
        taskExecutor = new WorkflowExecutionConfig().workflowDagTaskExecutor();
        executor = new WorkflowDagExecutor(productAnalysisAgent, knowledgeQaAgent, taskExecutor);
    }

    @AfterEach
    void tearDown() {
        taskExecutor.shutdown();
    }

    @Test
    void executesIndependentTasksInSameParallelWave() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        when(productAnalysisAgent.chat(any(ProductAnalysisChatRequest.class), any(AgentExecutionContext.class)))
                .thenAnswer(invocation -> {
                    awaitParallelPeer(started);
                    return productResponse();
                });
        when(knowledgeQaAgent.chat(any(KnowledgeQaChatRequest.class), any(AgentExecutionContext.class)))
                .thenAnswer(invocation -> {
                    awaitParallelPeer(started);
                    return knowledgeResponse();
                });

        DagExecutionResult result = executor.execute(
                independentPlan(), mixedRouting(), alignedContext(), "workflow-001", "step-001");

        assertThat(result.successCount()).isEqualTo(2);
        assertThat(result.failedCount()).isZero();
        assertThat(result.taskResults()).extracting(task -> task.status())
                .containsExactly(AgentTaskStatus.SUCCESS, AgentTaskStatus.SUCCESS);
        ArgumentCaptor<AgentExecutionContext> productContext = ArgumentCaptor.forClass(AgentExecutionContext.class);
        verify(productAnalysisAgent).chat(any(ProductAnalysisChatRequest.class), productContext.capture());
        assertThat(productContext.getValue().conversationMemoryEnabled()).isFalse();
    }

    @Test
    void skipsDependentTaskWhenPrerequisiteFails() {
        when(knowledgeQaAgent.chat(any(KnowledgeQaChatRequest.class), any(AgentExecutionContext.class)))
                .thenThrow(new IllegalStateException("knowledge model unavailable"));
        WorkflowPlan plan = new WorkflowPlan(
                "依赖失败测试",
                List.of(
                        new WorkflowPlanTask(
                                "task-1", 1, KnowledgeQaAgent.AGENT_NAME, "解释现金价值", List.of()),
                        new WorkflowPlanTask(
                                "task-2", 2, ProductAnalysisAgent.AGENT_NAME,
                                "分析产品现金价值", List.of("task-1"))),
                "task-2 依赖 task-1");

        DagExecutionResult result = executor.execute(
                plan, mixedRouting(), alignedContext(), "workflow-001", "step-001");

        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.taskResults()).extracting(task -> task.status())
                .containsExactly(AgentTaskStatus.FAILED, AgentTaskStatus.SKIPPED_DEPENDENCY_FAILED);
        verify(productAnalysisAgent, never()).chat(any(), any());
    }

    private void awaitParallelPeer(CountDownLatch started) throws InterruptedException {
        started.countDown();
        if (!started.await(2, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Tasks were not executed in parallel");
        }
    }

    private WorkflowPlan independentPlan() {
        return new WorkflowPlan(
                "解释犹豫期并分析产品",
                List.of(
                        new WorkflowPlanTask(
                                "task-1", 1, KnowledgeQaAgent.AGENT_NAME, "解释犹豫期", List.of()),
                        new WorkflowPlanTask(
                                "task-2", 2, ProductAnalysisAgent.AGENT_NAME, "分析 PA-001", List.of())),
                "两个任务无依赖");
    }

    private IntentRoutingResult mixedRouting() {
        return new IntentRoutingResult(
                IntentRecognitionNode.MULTI_INTENT,
                null,
                "混合意图",
                List.of(
                        new IntentRoute(IntentRecognitionNode.KNOWLEDGE_QA_INTENT,
                                KnowledgeQaAgent.AGENT_NAME, "解释犹豫期", "知识问答"),
                        new IntentRoute(IntentRecognitionNode.PRODUCT_ANALYSIS_INTENT,
                                ProductAnalysisAgent.AGENT_NAME, "分析 PA-001", "产品分析")));
    }

    private AlignedWorkflowContext alignedContext() {
        return new AlignedWorkflowContext(
                "conversation-001",
                "解释犹豫期并分析 PA-001",
                ConversationTopicRelation.NO_HISTORY,
                "解释保险合同犹豫期，并分析 PA-001 的退保风险",
                Map.of(),
                List.of(),
                new ProductRecallDecision(false, ProductRecallTrigger.NO_PRODUCT_MENTION, "无需召回"),
                List.of(),
                true,
                0,
                0,
                0,
                "trace-001",
                Instant.parse("2026-08-09T00:00:00Z"));
    }

    private ProductAnalysisChatResponse productResponse() {
        return new ProductAnalysisChatResponse(
                ProductAnalysisAgent.AGENT_NAME, "conversation-001", "pai-001", "产品分析结果",
                true, 100, Instant.parse("2026-08-09T00:00:01Z"), 6, false, 0, true, List.of());
    }

    private KnowledgeQaChatResponse knowledgeResponse() {
        return new KnowledgeQaChatResponse(
                KnowledgeQaAgent.AGENT_NAME, "conversation-001", "kqa-001", "知识问答结果",
                true, 100, Instant.parse("2026-08-09T00:00:01Z"), 6, false, 0);
    }
}
