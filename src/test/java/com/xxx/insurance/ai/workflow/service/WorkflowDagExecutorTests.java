package com.xxx.insurance.ai.workflow.service;

import com.xxx.insurance.ai.workflow.config.WorkflowExecutionConfig;
import com.xxx.insurance.ai.workflow.model.AgentTaskExecutionResult;
import com.xxx.insurance.ai.workflow.model.AgentTaskStatus;
import com.xxx.insurance.ai.workflow.model.AlignedWorkflowContext;
import com.xxx.insurance.ai.workflow.model.ConversationTopicRelation;
import com.xxx.insurance.ai.workflow.model.DagExecutionResult;
import com.xxx.insurance.ai.workflow.model.IntentRoute;
import com.xxx.insurance.ai.workflow.model.IntentRoutingResult;
import com.xxx.insurance.ai.workflow.model.ProductRecallDecision;
import com.xxx.insurance.ai.workflow.model.ProductRecallTrigger;
import com.xxx.insurance.ai.workflow.model.SubAgentExecutionResult;
import com.xxx.insurance.ai.workflow.model.WorkflowAgentTaskContext;
import com.xxx.insurance.ai.workflow.model.WorkflowPlan;
import com.xxx.insurance.ai.workflow.model.WorkflowPlanTask;
import com.xxx.insurance.ai.workflow.node.IntentRecognitionNode;
import com.xxx.insurance.asset.agent.AssetQueryAgent;
import com.xxx.insurance.knowledge.agent.KnowledgeQaAgent;
import com.xxx.insurance.policy.agent.PolicyQueryAgent;
import com.xxx.insurance.product.agent.ProductAnalysisAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowDagExecutorTests {

    private WorkflowTaskGraphRunner taskGraphRunner;

    private ThreadPoolTaskExecutor taskExecutor;

    private WorkflowDagExecutor executor;

    @BeforeEach
    void setUp() {
        taskGraphRunner = mock(WorkflowTaskGraphRunner.class);
        taskExecutor = new WorkflowExecutionConfig().workflowDagTaskExecutor();
        executor = new WorkflowDagExecutor(taskGraphRunner, taskExecutor);
    }

    @AfterEach
    void tearDown() {
        taskExecutor.shutdown();
    }

    @Test
    void executesSingleAgentTask() {
        when(taskGraphRunner.execute(any())).thenAnswer(invocation -> success(invocation.getArgument(0)));

        DagExecutionResult result = execute(plan(task("A", 1, ProductAnalysisAgent.AGENT_NAME)));

        assertThat(result.successCount()).isEqualTo(1);
        verify(taskGraphRunner).execute(any());
    }

    @Test
    void executesTwoTasksSerially() {
        List<String> starts = java.util.Collections.synchronizedList(new ArrayList<>());
        when(taskGraphRunner.execute(any())).thenAnswer(invocation -> {
            WorkflowAgentTaskContext context = invocation.getArgument(0);
            starts.add(context.task().taskId());
            return success(context);
        });

        execute(plan(
                task("A", 1, ProductAnalysisAgent.AGENT_NAME),
                task("B", 2, KnowledgeQaAgent.AGENT_NAME, "A")));

        assertThat(starts).containsExactly("A", "B");
    }

    @Test
    void executesIndependentTasksInParallel() {
        CountDownLatch started = new CountDownLatch(2);
        when(taskGraphRunner.execute(any())).thenAnswer(invocation -> {
            WorkflowAgentTaskContext context = invocation.getArgument(0);
            started.countDown();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            return success(context);
        });

        DagExecutionResult result = execute(plan(
                task("A", 1, ProductAnalysisAgent.AGENT_NAME),
                task("B", 2, KnowledgeQaAgent.AGENT_NAME)));

        assertThat(result.successCount()).isEqualTo(2);
    }

    @Test
    void supportsSerialThenParallel() {
        executeWithSuccess(plan(
                task("A", 1, ProductAnalysisAgent.AGENT_NAME),
                task("B", 2, KnowledgeQaAgent.AGENT_NAME, "A"),
                task("C", 3, PolicyQueryAgent.AGENT_NAME, "A")));
    }

    @Test
    void supportsParallelThenSerialJoin() {
        List<WorkflowAgentTaskContext> calls = executeWithSuccess(plan(
                task("A", 1, ProductAnalysisAgent.AGENT_NAME),
                task("B", 2, KnowledgeQaAgent.AGENT_NAME),
                task("C", 3, PolicyQueryAgent.AGENT_NAME, "A", "B")));

        WorkflowAgentTaskContext join = calls.stream()
                .filter(call -> call.task().taskId().equals("C"))
                .findFirst().orElseThrow();
        assertThat(join.dependencyResults()).extracting(AgentTaskExecutionResult::taskId)
                .containsExactly("A", "B");
    }

    @Test
    void supportsRepeatedSerialParallelAlternation() {
        DagExecutionResult result = executeWithSuccessResult(plan(
                task("A", 1, ProductAnalysisAgent.AGENT_NAME),
                task("C", 2, AssetQueryAgent.AGENT_NAME),
                task("B", 3, KnowledgeQaAgent.AGENT_NAME, "A"),
                task("D", 4, PolicyQueryAgent.AGENT_NAME, "B", "C"),
                task("E", 5, ProductAnalysisAgent.AGENT_NAME, "D"),
                task("F", 6, KnowledgeQaAgent.AGENT_NAME, "D"),
                task("G", 7, AssetQueryAgent.AGENT_NAME, "E", "F")));

        assertThat(result.successCount()).isEqualTo(7);
    }

    @Test
    void startsBImmediatelyAfterAWithoutWaitingForIndependentC() throws Exception {
        CountDownLatch aAndCStarted = new CountDownLatch(2);
        CountDownLatch bStarted = new CountDownLatch(1);
        CountDownLatch releaseC = new CountDownLatch(1);
        when(taskGraphRunner.execute(any())).thenAnswer(invocation -> {
            WorkflowAgentTaskContext context = invocation.getArgument(0);
            switch (context.task().taskId()) {
                case "A" -> {
                    aAndCStarted.countDown();
                    assertThat(aAndCStarted.await(2, TimeUnit.SECONDS)).isTrue();
                }
                case "C" -> {
                    aAndCStarted.countDown();
                    assertThat(releaseC.await(3, TimeUnit.SECONDS)).isTrue();
                }
                case "B" -> bStarted.countDown();
                default -> throw new IllegalStateException("Unexpected task");
            }
            return success(context);
        });

        var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> execute(plan(
                task("A", 1, ProductAnalysisAgent.AGENT_NAME),
                task("C", 2, AssetQueryAgent.AGENT_NAME),
                task("B", 3, KnowledgeQaAgent.AGENT_NAME, "A"))));

        assertThat(bStarted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(future.isDone()).isFalse();
        releaseC.countDown();
        assertThat(future.get(3, TimeUnit.SECONDS).successCount()).isEqualTo(3);
    }

    @Test
    void joinTaskWaitsForAllDeclaredUpstreams() {
        CountDownLatch releaseB = new CountDownLatch(1);
        CountDownLatch cStarted = new CountDownLatch(1);
        when(taskGraphRunner.execute(any())).thenAnswer(invocation -> {
            WorkflowAgentTaskContext context = invocation.getArgument(0);
            if (context.task().taskId().equals("B")) {
                releaseB.await(2, TimeUnit.SECONDS);
            }
            if (context.task().taskId().equals("C")) {
                cStarted.countDown();
            }
            return success(context);
        });

        var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> execute(plan(
                task("A", 1, ProductAnalysisAgent.AGENT_NAME),
                task("B", 2, KnowledgeQaAgent.AGENT_NAME),
                task("C", 3, PolicyQueryAgent.AGENT_NAME, "A", "B"))));
        assertThat(cStarted.getCount()).isEqualTo(1);
        releaseB.countDown();
        assertThat(future.join().successCount()).isEqualTo(3);
        assertThat(cStarted.getCount()).isZero();
    }

    @Test
    void parallelResultsAreKeptByTaskId() {
        DagExecutionResult result = executeWithSuccessResult(plan(
                task("A", 1, ProductAnalysisAgent.AGENT_NAME),
                task("B", 2, ProductAnalysisAgent.AGENT_NAME),
                task("C", 3, ProductAnalysisAgent.AGENT_NAME)));

        assertThat(result.taskResults()).extracting(AgentTaskExecutionResult::taskId)
                .containsExactly("A", "B", "C");
        assertThat(result.taskResults()).extracting(item -> item.response().answer())
                .containsExactly("answer-A", "answer-B", "answer-C");
    }

    @Test
    void failedDependencySkipsSuccessorWhileIndependentTaskContinues() {
        when(taskGraphRunner.execute(any())).thenAnswer(invocation -> {
            WorkflowAgentTaskContext context = invocation.getArgument(0);
            return context.task().taskId().equals("A") ? failed(context, 2) : success(context);
        });

        DagExecutionResult result = execute(plan(
                task("A", 1, ProductAnalysisAgent.AGENT_NAME),
                task("B", 2, KnowledgeQaAgent.AGENT_NAME, "A"),
                task("C", 3, AssetQueryAgent.AGENT_NAME)));

        assertThat(result.taskResults()).extracting(AgentTaskExecutionResult::status)
                .containsExactly(AgentTaskStatus.FAILED,
                        AgentTaskStatus.SKIPPED_DEPENDENCY_FAILED,
                        AgentTaskStatus.SUCCESS);
        assertThat(result.taskResults().getFirst().attempts()).isEqualTo(2);
    }

    private List<WorkflowAgentTaskContext> executeWithSuccess(WorkflowPlan plan) {
        List<WorkflowAgentTaskContext> calls = java.util.Collections.synchronizedList(new ArrayList<>());
        when(taskGraphRunner.execute(any())).thenAnswer(invocation -> {
            WorkflowAgentTaskContext context = invocation.getArgument(0);
            calls.add(context);
            return success(context);
        });
        execute(plan);
        return calls;
    }

    private DagExecutionResult executeWithSuccessResult(WorkflowPlan plan) {
        when(taskGraphRunner.execute(any())).thenAnswer(invocation -> success(invocation.getArgument(0)));
        return execute(plan);
    }

    private DagExecutionResult execute(WorkflowPlan plan) {
        return executor.execute(plan, routing(), alignedContext(), "workflow-001", 3L, "step-001", true);
    }

    private WorkflowPlan plan(WorkflowPlanTask... tasks) {
        return new WorkflowPlan("test", List.of(tasks), "test DAG");
    }

    private WorkflowPlanTask task(String id, int sequence, String agentType, String... dependencies) {
        return new WorkflowPlanTask(id, sequence, agentType, "query-" + id,
                List.of(dependencies), 1, true);
    }

    private AgentTaskExecutionResult success(WorkflowAgentTaskContext context) {
        Instant now = Instant.now();
        String answer = "answer-" + context.task().taskId();
        return new AgentTaskExecutionResult(
                context.task().taskId(), context.task().sequence(), context.task().agentType(),
                AgentTaskStatus.SUCCESS,
                new SubAgentExecutionResult(context.task().agentType(), context.conversationId(),
                        "inv-" + context.task().taskId(), answer, false, 1, now,
                        answer.length(), false, 0),
                null, null, now, now, 1, 1);
    }

    private AgentTaskExecutionResult failed(WorkflowAgentTaskContext context, int attempts) {
        Instant now = Instant.now();
        return new AgentTaskExecutionResult(
                context.task().taskId(), context.task().sequence(), context.task().agentType(),
                AgentTaskStatus.FAILED, null, "FAILED", "failure", now, now, 1, attempts);
    }

    private IntentRoutingResult routing() {
        return new IntentRoutingResult(
                IntentRecognitionNode.MULTI_INTENT, null, "test",
                List.of(
                        route(IntentRecognitionNode.PRODUCT_ANALYSIS_INTENT, ProductAnalysisAgent.AGENT_NAME),
                        route(IntentRecognitionNode.KNOWLEDGE_QA_INTENT, KnowledgeQaAgent.AGENT_NAME),
                        route(IntentRecognitionNode.POLICY_QUERY_INTENT, PolicyQueryAgent.AGENT_NAME),
                        route(IntentRecognitionNode.ASSET_QUERY_INTENT, AssetQueryAgent.AGENT_NAME)));
    }

    private IntentRoute route(String intent, String agent) {
        return new IntentRoute(intent, agent, "query", "test");
    }

    private AlignedWorkflowContext alignedContext() {
        return new AlignedWorkflowContext(
                "conversation-001", "original", ConversationTopicRelation.NO_HISTORY, "rewritten",
                Map.of(), List.of(),
                new ProductRecallDecision(false, ProductRecallTrigger.NO_PRODUCT_MENTION, "none"),
                List.of(), true, 0, 0, 0, "trace-001", Instant.now());
    }
}
