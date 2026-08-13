package com.xxx.insurance.ai.workflow.execution;

import com.xxx.insurance.ai.workflow.model.IntentRoutingResult;
import com.xxx.insurance.ai.workflow.model.WorkflowPlan;
import com.xxx.insurance.ai.workflow.model.WorkflowPlanTask;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

/** Planner 输出进入执行层前的确定性安全边界。 */
@Component
public class WorkflowPlanValidator {

    private static final int MAX_TASK_COUNT = 12;

    private static final int MAX_QUERY_LENGTH = 2000;

    private static final int MAX_RETRIES = 3;

    /**
     * 校验任务字段、Agent 白名单、依赖引用和 DAG 无环性。
     *
     * <p>executionMode 不参与任何校验或执行；真正的串并行关系只由 dependsOn 决定。</p>
     */
    public WorkflowPlan validate(WorkflowPlan plan, IntentRoutingResult routingResult) {
        Objects.requireNonNull(plan, "Workflow plan must not be null");
        Objects.requireNonNull(routingResult, "Intent routing result must not be null");
        if (!StringUtils.hasText(plan.objective())) {
            throw new IllegalArgumentException("Workflow plan objective must not be blank");
        }
        if (routingResult.routes() == null || routingResult.routes().isEmpty()
                || routingResult.routes().size() > 4) {
            throw new IllegalArgumentException("Workflow requires one to four intent routes");
        }
        if (plan.tasks() == null || plan.tasks().isEmpty() || plan.tasks().size() > MAX_TASK_COUNT) {
            throw new IllegalArgumentException("Workflow plan requires one to twelve tasks");
        }

        Set<String> allowedAgents = routingResult.routes().stream()
                .map(route -> route.targetAgent())
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> taskIds = new HashSet<>();
        Map<String, WorkflowPlanTask> tasksById = new HashMap<>();
        Set<Integer> sequences = new HashSet<>();

        for (WorkflowPlanTask task : plan.tasks()) {
            validateTask(task, allowedAgents, taskIds, sequences);
            tasksById.put(task.taskId(), task);
        }
        for (int sequence = 1; sequence <= plan.tasks().size(); sequence++) {
            if (!sequences.contains(sequence)) {
                throw new IllegalArgumentException("Workflow task sequence must be consecutive from 1");
            }
        }
        validateDependencies(tasksById);
        validateAcyclic(tasksById);
        return plan;
    }

    /** 校验一个任务的稳定标识、白名单、查询、重试和展示序号。 */
    private void validateTask(WorkflowPlanTask task,
                              Set<String> allowedAgents,
                              Set<String> taskIds,
                              Set<Integer> sequences) {
        if (task == null || !StringUtils.hasText(task.taskId()) || !taskIds.add(task.taskId())) {
            throw new IllegalArgumentException("Workflow taskId must be non-blank and unique");
        }
        if (task.sequence() <= 0 || !sequences.add(task.sequence())) {
            throw new IllegalArgumentException("Workflow task sequence must be positive and unique");
        }
        if (!StringUtils.hasText(task.agentType()) || !allowedAgents.contains(task.agentType())) {
            throw new IllegalArgumentException("Workflow task agentType is outside intent whitelist: "
                    + task.agentType());
        }
        if (!StringUtils.hasText(task.query()) || task.query().length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("Workflow task query must be non-blank and at most 2000 characters");
        }
        if (task.maxRetries() < 0 || task.maxRetries() > MAX_RETRIES) {
            throw new IllegalArgumentException("Workflow task maxRetries must be between 0 and 3");
        }
    }

    /** 校验依赖存在、无重复且任务不依赖自身。 */
    private void validateDependencies(Map<String, WorkflowPlanTask> tasksById) {
        for (WorkflowPlanTask task : tasksById.values()) {
            List<String> dependencies = task.dependsOn();
            if (new HashSet<>(dependencies).size() != dependencies.size()) {
                throw new IllegalArgumentException("Workflow task dependencies must not contain duplicates");
            }
            for (String dependency : dependencies) {
                if (!tasksById.containsKey(dependency)) {
                    throw new IllegalArgumentException("Workflow dependency does not exist: " + dependency);
                }
                if (task.taskId().equals(dependency)) {
                    throw new IllegalArgumentException("Workflow task cannot depend on itself: " + task.taskId());
                }
            }
        }
    }

    /** 使用 Kahn 算法拒绝环及因此无法满足的依赖集合。 */
    private void validateAcyclic(Map<String, WorkflowPlanTask> tasksById) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, Set<String>> successors = new HashMap<>();
        tasksById.keySet().forEach(taskId -> {
            indegree.put(taskId, 0);
            successors.put(taskId, new HashSet<>());
        });
        tasksById.values().forEach(task -> task.dependsOn().forEach(dependency -> {
            indegree.compute(task.taskId(), (ignored, value) -> value + 1);
            successors.get(dependency).add(task.taskId());
        }));

        Queue<String> ready = new ArrayDeque<>();
        indegree.forEach((taskId, degree) -> {
            if (degree == 0) {
                ready.add(taskId);
            }
        });
        int visited = 0;
        while (!ready.isEmpty()) {
            String taskId = ready.remove();
            visited++;
            for (String successor : successors.get(taskId)) {
                int degree = indegree.compute(successor, (ignored, value) -> value - 1);
                if (degree == 0) {
                    ready.add(successor);
                }
            }
        }
        if (visited != tasksById.size()) {
            throw new IllegalArgumentException("Workflow dependency graph contains a cycle");
        }
    }
}
