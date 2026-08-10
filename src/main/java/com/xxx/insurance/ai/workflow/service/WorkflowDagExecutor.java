package com.xxx.insurance.ai.workflow.service;

import com.xxx.insurance.ai.agent.AgentExecutionContext;
import com.xxx.insurance.ai.workflow.config.WorkflowExecutionConfig;
import com.xxx.insurance.ai.workflow.model.AgentTaskExecutionResult;
import com.xxx.insurance.ai.workflow.model.AgentTaskStatus;
import com.xxx.insurance.ai.workflow.model.AlignedWorkflowContext;
import com.xxx.insurance.ai.workflow.model.DagExecutionResult;
import com.xxx.insurance.ai.workflow.model.IntentRoute;
import com.xxx.insurance.ai.workflow.model.IntentRoutingResult;
import com.xxx.insurance.ai.workflow.model.SubAgentExecutionResult;
import com.xxx.insurance.ai.workflow.model.WorkflowPlan;
import com.xxx.insurance.ai.workflow.model.WorkflowPlanTask;
import com.xxx.insurance.knowledge.agent.KnowledgeQaAgent;
import com.xxx.insurance.knowledge.model.KnowledgeQaChatRequest;
import com.xxx.insurance.knowledge.model.KnowledgeQaChatResponse;
import com.xxx.insurance.product.agent.ProductAnalysisAgent;
import com.xxx.insurance.product.model.ProductAnalysisChatRequest;
import com.xxx.insurance.product.model.ProductAnalysisChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 执行 Planner 生成的受控小型 DAG。
 *
 * <p>Main Graph 管理跨节点的确定性生命周期；本服务管理运行时才能确定的子智能体任务图。
 * 每一轮同时提交所有依赖已经成功的任务，依赖失败的后继任务直接标记为跳过。单个 Agent
 * 异常被收敛为任务结果，不会阻止无依赖任务继续执行和最终汇总。</p>
 */
@Service
public class WorkflowDagExecutor {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDagExecutor.class);

    private static final String ERROR_AGENT_INVOKE_FAILED = "AGENT_INVOKE_FAILED";

    private static final String ERROR_DEPENDENCY_FAILED = "DEPENDENCY_FAILED";

    private final ProductAnalysisAgent productAnalysisAgent;

    private final KnowledgeQaAgent knowledgeQaAgent;

    private final ThreadPoolTaskExecutor taskExecutor;

    /**
     * 创建动态 DAG 执行服务，并注入当前允许调度的子智能体和专属线程池。
     */
    public WorkflowDagExecutor(ProductAnalysisAgent productAnalysisAgent,
                               KnowledgeQaAgent knowledgeQaAgent,
                               @Qualifier(WorkflowExecutionConfig.WORKFLOW_DAG_TASK_EXECUTOR)
                               ThreadPoolTaskExecutor taskExecutor) {
        this.productAnalysisAgent = productAnalysisAgent;
        this.knowledgeQaAgent = knowledgeQaAgent;
        this.taskExecutor = taskExecutor;
    }

    /**
     * 按依赖波次执行计划，并始终返回所有任务的明确终态。
     */
    public DagExecutionResult execute(WorkflowPlan plan,
                                      IntentRoutingResult routingResult,
                                      AlignedWorkflowContext context,
                                      String workflowInstanceId,
                                      String workflowStepId) {
        Map<String, WorkflowPlanTask> pending = new LinkedHashMap<>();
        plan.tasks().stream()
                .sorted(java.util.Comparator.comparingInt(WorkflowPlanTask::sequence))
                .forEach(task -> pending.put(task.taskId(), task));
        Map<String, AgentTaskExecutionResult> completed = new LinkedHashMap<>();
        Map<String, IntentRoute> routesByAgent = routingResult.routes().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        IntentRoute::targetAgent,
                        route -> route));

        while (!pending.isEmpty()) {
            boolean skippedAny = skipTasksWithFailedDependencies(pending, completed);
            List<WorkflowPlanTask> readyTasks = pending.values().stream()
                    .filter(task -> dependenciesSucceeded(task, completed))
                    .toList();
            if (readyTasks.isEmpty()) {
                if (skippedAny) {
                    continue;
                }
                throw new IllegalStateException("Workflow DAG has unresolved or cyclic dependencies");
            }

            log.info("[Workflow] node=dag-executor action=execute-wave workflowInstanceId={} readyTasks={}",
                    workflowInstanceId,
                    readyTasks.stream().map(WorkflowPlanTask::taskId).toList());
            List<CompletableFuture<AgentTaskExecutionResult>> futures = readyTasks.stream()
                    .map(task -> CompletableFuture.supplyAsync(
                            () -> executeTask(task, routesByAgent, context, workflowInstanceId, workflowStepId),
                            taskExecutor))
                    .toList();
            for (int index = 0; index < readyTasks.size(); index++) {
                WorkflowPlanTask task = readyTasks.get(index);
                completed.put(task.taskId(), futures.get(index).join());
                pending.remove(task.taskId());
            }
        }
        return DagExecutionResult.from(new ArrayList<>(completed.values()));
    }

    /**
     * 执行单个计划任务，将成功响应或调用异常转换成统一任务终态。
     */
    private AgentTaskExecutionResult executeTask(WorkflowPlanTask task,
                                                  Map<String, IntentRoute> routesByAgent,
                                                  AlignedWorkflowContext context,
                                                  String workflowInstanceId,
                                                  String workflowStepId) {
        Instant startedAt = Instant.now();
        try {
            IntentRoute route = routesByAgent.get(task.agentName());
            if (route == null) {
                throw new IllegalStateException("Task target agent is outside intent routing whitelist: "
                        + task.agentName());
            }
            AgentExecutionContext executionContext = new AgentExecutionContext(
                    workflowInstanceId,
                    workflowStepId,
                    context.originalQuestion(),
                    false);
            SubAgentExecutionResult response = invokeAgent(task, executionContext, context.conversationId());
            Instant endedAt = Instant.now();
            return new AgentTaskExecutionResult(
                    task.taskId(), task.sequence(), task.agentName(), AgentTaskStatus.SUCCESS,
                    response, null, null, startedAt, endedAt, duration(startedAt, endedAt));
        }
        catch (Exception ex) {
            Instant endedAt = Instant.now();
            log.error("[Workflow] node=dag-executor action=execute-task status=failed taskId={} agent={}",
                    task.taskId(), task.agentName(), ex);
            return new AgentTaskExecutionResult(
                    task.taskId(), task.sequence(), task.agentName(), AgentTaskStatus.FAILED,
                    null, ERROR_AGENT_INVOKE_FAILED, truncate(ex.getMessage()),
                    startedAt, endedAt, duration(startedAt, endedAt));
        }
    }

    /**
     * 根据 Planner 任务中的白名单 Agent 名称调用对应业务智能体。
     */
    private SubAgentExecutionResult invokeAgent(WorkflowPlanTask task,
                                                 AgentExecutionContext executionContext,
                                                 String conversationId) {
        if (ProductAnalysisAgent.AGENT_NAME.equals(task.agentName())) {
            return from(productAnalysisAgent.chat(
                    new ProductAnalysisChatRequest(task.instruction(), conversationId),
                    executionContext));
        }
        if (KnowledgeQaAgent.AGENT_NAME.equals(task.agentName())) {
            return from(knowledgeQaAgent.chat(
                    new KnowledgeQaChatRequest(task.instruction(), conversationId),
                    executionContext));
        }
        throw new IllegalStateException("Unsupported workflow agent: " + task.agentName());
    }

    /**
     * 找出依赖已经失败的待执行任务，并将其标记为依赖失败跳过。
     */
    private boolean skipTasksWithFailedDependencies(Map<String, WorkflowPlanTask> pending,
                                                     Map<String, AgentTaskExecutionResult> completed) {
        List<WorkflowPlanTask> skippedTasks = pending.values().stream()
                .filter(task -> dependenciesFailed(task, completed))
                .toList();
        Instant now = Instant.now();
        skippedTasks.forEach(task -> {
            completed.put(task.taskId(), new AgentTaskExecutionResult(
                    task.taskId(), task.sequence(), task.agentName(), AgentTaskStatus.SKIPPED_DEPENDENCY_FAILED,
                    null, ERROR_DEPENDENCY_FAILED, "A prerequisite task did not complete successfully",
                    now, now, 0));
            pending.remove(task.taskId());
        });
        return !skippedTasks.isEmpty();
    }

    /**
     * 判断任务的全部前置依赖是否已经成功完成。
     */
    private boolean dependenciesSucceeded(WorkflowPlanTask task,
                                          Map<String, AgentTaskExecutionResult> completed) {
        List<String> dependencies = dependencies(task);
        return dependencies.stream().allMatch(dependency -> completed.containsKey(dependency)
                && completed.get(dependency).status() == AgentTaskStatus.SUCCESS);
    }

    /**
     * 判断任务是否存在失败或被跳过的已完成依赖。
     */
    private boolean dependenciesFailed(WorkflowPlanTask task,
                                       Map<String, AgentTaskExecutionResult> completed) {
        return dependencies(task).stream()
                .map(completed::get)
                .anyMatch(result -> result != null && result.status() != AgentTaskStatus.SUCCESS);
    }

    /**
     * 统一将 Planner 可能返回的 null 依赖转换为空列表。
     */
    private List<String> dependencies(WorkflowPlanTask task) {
        return task.dependsOn() == null ? List.of() : task.dependsOn();
    }

    /**
     * 计算任务执行耗时。
     */
    private long duration(Instant startedAt, Instant endedAt) {
        return Duration.between(startedAt, endedAt).toMillis();
    }

    /**
     * 限制持久化错误信息长度，避免超过数据库字段上限。
     */
    private String truncate(String message) {
        if (message == null || message.length() <= 1024) {
            return message;
        }
        return message.substring(0, 1024);
    }

    /**
     * 将产品分析智能体响应投影为工作流统一响应。
     */
    private SubAgentExecutionResult from(ProductAnalysisChatResponse response) {
        return new SubAgentExecutionResult(
                response.agentName(), response.conversationId(), response.invocationId(), response.answer(),
                response.modelInvoked(), response.durationMs(), response.answeredAt(), response.answerLength(),
                response.memoryEnabled(), response.memoryMessageCount());
    }

    /**
     * 将知识问答智能体响应投影为工作流统一响应。
     */
    private SubAgentExecutionResult from(KnowledgeQaChatResponse response) {
        return new SubAgentExecutionResult(
                response.agentName(), response.conversationId(), response.invocationId(), response.answer(),
                response.modelInvoked(), response.durationMs(), response.answeredAt(), response.answerLength(),
                response.memoryEnabled(), response.memoryMessageCount());
    }
}
