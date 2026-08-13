package com.xxx.insurance.ai.workflow.execution;

import com.xxx.insurance.ai.workflow.config.WorkflowExecutionConfig;
import com.xxx.insurance.ai.workflow.model.AgentTaskExecutionResult;
import com.xxx.insurance.ai.workflow.model.AgentTaskStatus;
import com.xxx.insurance.ai.workflow.model.AlignedWorkflowContext;
import com.xxx.insurance.ai.workflow.model.DagExecutionResult;
import com.xxx.insurance.ai.workflow.model.IntentRoutingResult;
import com.xxx.insurance.ai.workflow.model.WorkflowAgentTaskContext;
import com.xxx.insurance.ai.workflow.model.WorkflowPlan;
import com.xxx.insurance.ai.workflow.model.WorkflowPlanTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;

/**
 * 按 dependsOn 执行任意无环子智能体任务图。
 *
 * <p>调度器不使用 executionMode，也不按波次统一等待。每个任务完成后立即重新计算就绪任务，
 * 因此某个后继只等待自己的明确上游。每个实际任务由独立 Spring AI Alibaba 子图执行并持久化，
 * 本类只维护当前调度轮次的不可变任务输入和终态索引。</p>
 */
@Service
public class WorkflowDagExecutor {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDagExecutor.class);

    private static final String ERROR_DEPENDENCY_FAILED = "DEPENDENCY_FAILED";

    private final WorkflowTaskGraphRunner taskGraphRunner;

    private final ThreadPoolTaskExecutor taskExecutor;

    /** 创建事件驱动 DAG 调度器，并复用项目有界线程池执行任务子图。 */
    public WorkflowDagExecutor(
            WorkflowTaskGraphRunner taskGraphRunner,
            @Qualifier(WorkflowExecutionConfig.WORKFLOW_DAG_TASK_EXECUTOR)
            ThreadPoolTaskExecutor taskExecutor) {
        this.taskGraphRunner = taskGraphRunner;
        this.taskExecutor = taskExecutor;
    }

    /**
     * 执行 Planner 产生的受控依赖图。先按展示序号建立稳定任务索引并从意图路由结果生成 Agent 白名单，
     * 然后循环传播依赖失败、计算 READY 任务、仅向每个任务注入明确上游成功结果，并提交到项目有界线程池。
     * 任意任务完成后立即更新终态索引并重新释放后继，不等待无关并行分支；依赖失败的任务递归标记为
     * SKIPPED_DEPENDENCY_FAILED，独立任务继续运行。任务实际 Agent 调用与 Checkpoint 由独立子图负责，
     * 系统级子图/Checkpoint 异常才取消剩余 Future 并终止主 DAG，最终按 taskId 聚合不可变结果。
     */
    public DagExecutionResult execute(WorkflowPlan plan,
                                      IntentRoutingResult routingResult,
                                      AlignedWorkflowContext context,
                                      String workflowInstanceId,
                                      long executionFenceToken,
                                      String workflowStepId,
                                      boolean tokenStreamingEnabled) {
        Map<String, WorkflowPlanTask> pending = orderedTasks(plan);
        Map<String, AgentTaskExecutionResult> completed = new LinkedHashMap<>();
        Map<Future<AgentTaskExecutionResult>, WorkflowPlanTask> running = new HashMap<>();
        CompletionService<AgentTaskExecutionResult> completions =
                new ExecutorCompletionService<>(taskExecutor.getThreadPoolExecutor());
        var allowedAgents = routingResult.routes().stream()
                .map(route -> route.targetAgent())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        while (!pending.isEmpty() || !running.isEmpty()) {
            propagateDependencyFailures(pending, completed);
            List<WorkflowPlanTask> readyTasks = pending.values().stream()
                    .filter(task -> dependenciesSucceeded(task, completed))
                    .toList();
            for (WorkflowPlanTask task : readyTasks) {
                if (!allowedAgents.contains(task.agentType())) {
                    throw new IllegalStateException("Task agent is outside routing whitelist: " + task.agentType());
                }
                List<AgentTaskExecutionResult> dependencyResults = dependencyResults(task, completed);
                WorkflowAgentTaskContext taskContext = new WorkflowAgentTaskContext(
                        task,
                        context.conversationId(),
                        workflowInstanceId,
                        executionFenceToken,
                        workflowStepId,
                        context.originalQuestion(),
                        context.resolvedProducts(),
                        dependencyResults,
                        tokenStreamingEnabled);
                Future<AgentTaskExecutionResult> future = completions.submit(
                        () -> taskGraphRunner.execute(taskContext));
                running.put(future, task);
                pending.remove(task.taskId());
                log.info("[Workflow] node=dag-executor action=submit taskId={} agentType={} dependsOn={}",
                        task.taskId(), task.agentType(), task.dependsOn());
            }

            if (running.isEmpty()) {
                if (!pending.isEmpty()) {
                    throw new IllegalStateException("Workflow DAG has unresolved dependencies");
                }
                continue;
            }
            AgentTaskExecutionResult result = awaitNext(completions, running);
            completed.put(result.taskId(), result);
            log.info("[Workflow] node=dag-executor action=complete taskId={} status={} attempts={}",
                    result.taskId(), result.status(), result.attempts());
        }
        return DagExecutionResult.from(new ArrayList<>(completed.values()));
    }

    /** 按展示序号建立稳定的待执行索引；执行顺序不依赖此排序。 */
    private Map<String, WorkflowPlanTask> orderedTasks(WorkflowPlan plan) {
        Map<String, WorkflowPlanTask> tasks = new LinkedHashMap<>();
        plan.tasks().stream()
                .sorted(Comparator.comparingInt(WorkflowPlanTask::sequence))
                .forEach(task -> tasks.put(task.taskId(), task));
        return tasks;
    }

    /** 等待任意一个运行任务完成；异常代表任务子图或 Checkpoint 的系统级失败。 */
    private AgentTaskExecutionResult awaitNext(
            CompletionService<AgentTaskExecutionResult> completions,
            Map<Future<AgentTaskExecutionResult>, WorkflowPlanTask> running) {
        try {
            Future<AgentTaskExecutionResult> completedFuture = completions.take();
            running.remove(completedFuture);
            return completedFuture.get();
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            running.keySet().forEach(future -> future.cancel(true));
            throw new IllegalStateException("Workflow DAG execution interrupted", ex);
        }
        catch (ExecutionException ex) {
            running.keySet().forEach(future -> future.cancel(true));
            throw new IllegalStateException("Workflow task graph execution failed", ex.getCause());
        }
    }

    /** 递归标记所有已确定依赖失败的待执行任务。 */
    private void propagateDependencyFailures(Map<String, WorkflowPlanTask> pending,
                                             Map<String, AgentTaskExecutionResult> completed) {
        boolean changed;
        do {
            changed = false;
            List<WorkflowPlanTask> skipped = pending.values().stream()
                    .filter(task -> dependenciesFailed(task, completed))
                    .toList();
            for (WorkflowPlanTask task : skipped) {
                Instant now = Instant.now();
                completed.put(task.taskId(), new AgentTaskExecutionResult(
                        task.taskId(), task.sequence(), task.agentType(),
                        AgentTaskStatus.SKIPPED_DEPENDENCY_FAILED, null,
                        ERROR_DEPENDENCY_FAILED,
                        "A prerequisite task did not complete successfully",
                        now, now, 0, 0));
                pending.remove(task.taskId());
                changed = true;
            }
        }
        while (changed);
    }

    /** 判断全部明确依赖是否成功；无依赖任务立即 READY。 */
    private boolean dependenciesSucceeded(WorkflowPlanTask task,
                                          Map<String, AgentTaskExecutionResult> completed) {
        return task.dependsOn().stream().allMatch(dependency -> {
            AgentTaskExecutionResult result = completed.get(dependency);
            return result != null && result.status() == AgentTaskStatus.SUCCESS;
        });
    }

    /** 判断是否已有任一明确依赖进入失败或跳过终态。 */
    private boolean dependenciesFailed(WorkflowPlanTask task,
                                       Map<String, AgentTaskExecutionResult> completed) {
        return task.dependsOn().stream()
                .map(completed::get)
                .anyMatch(result -> result != null && result.status() != AgentTaskStatus.SUCCESS);
    }

    /** 仅按 dependsOn 顺序提供成功上游结果，不泄露其他并行分支数据。 */
    private List<AgentTaskExecutionResult> dependencyResults(
            WorkflowPlanTask task,
            Map<String, AgentTaskExecutionResult> completed) {
        return task.dependsOn().stream().map(completed::get).toList();
    }
}
