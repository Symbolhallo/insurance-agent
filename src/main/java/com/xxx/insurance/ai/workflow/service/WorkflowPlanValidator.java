package com.xxx.insurance.ai.workflow.service;

import com.xxx.insurance.ai.workflow.model.IntentRoutingResult;
import com.xxx.insurance.ai.workflow.model.WorkflowPlan;
import com.xxx.insurance.ai.workflow.model.WorkflowPlanTask;
import com.xxx.insurance.ai.workflow.node.IntentRecognitionNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Planner 输出的确定性安全边界。
 */
@Component
public class WorkflowPlanValidator {

    /**
     * 校验 Planner 计划的任务数量、编号、Agent 白名单、指令长度和依赖方向。
     *
     * @param plan 模型生成的结构化计划
     * @param routingResult 意图识别阶段给出的唯一允许路由集合
     * @return 校验通过的原计划
     * @throws IllegalArgumentException 计划违反 Planner v2 确定性约束时抛出
     */
    public WorkflowPlan validate(WorkflowPlan plan, IntentRoutingResult routingResult) {
        Objects.requireNonNull(plan, "Workflow plan must not be null");
        Objects.requireNonNull(routingResult, "Intent routing result must not be null");
        if (!IntentRecognitionNode.PRODUCT_ANALYSIS_INTENT.equals(routingResult.intent())
                && !IntentRecognitionNode.KNOWLEDGE_QA_INTENT.equals(routingResult.intent())
                && !IntentRecognitionNode.MULTI_INTENT.equals(routingResult.intent())) {
            throw new IllegalArgumentException("Planner v2 does not support intent: " + routingResult.intent());
        }
        if (!StringUtils.hasText(plan.objective())) {
            throw new IllegalArgumentException("Workflow plan objective must not be blank");
        }
        if (routingResult.routes() == null || routingResult.routes().isEmpty()
                || routingResult.routes().size() > 2) {
            throw new IllegalArgumentException("Planner v2 requires one or two intent routes");
        }
        if (plan.tasks() == null || plan.tasks().size() != routingResult.routes().size()) {
            throw new IllegalArgumentException("Planner task count must match intent route count");
        }

        Set<String> allowedAgents = routingResult.routes().stream()
                .map(route -> route.targetAgent())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> taskIds = new HashSet<>();
        Set<String> usedAgents = new HashSet<>();
        Map<String, Integer> sequenceByTaskId = new HashMap<>();

        for (int index = 0; index < plan.tasks().size(); index++) {
            WorkflowPlanTask task = plan.tasks().get(index);
            int expectedSequence = index + 1;
            String expectedTaskId = "task-" + expectedSequence;
            String expectedAgent = routingResult.routes().get(index).targetAgent();
            if (task == null || !expectedTaskId.equals(task.taskId()) || task.sequence() != expectedSequence) {
                throw new IllegalArgumentException(
                        "Planner tasks must use consecutive taskId and sequence starting at 1");
            }
            if (!taskIds.add(task.taskId())) {
                throw new IllegalArgumentException("Planner taskId must be unique: " + task.taskId());
            }
            if (!Objects.equals(expectedAgent, task.agentName())
                    || !allowedAgents.contains(task.agentName())
                    || !usedAgents.add(task.agentName())) {
                throw new IllegalArgumentException("Planner target agent is not allowed or duplicated: "
                        + task.agentName());
            }
            if (!StringUtils.hasText(task.instruction())) {
                throw new IllegalArgumentException("Workflow plan task instruction must not be blank");
            }
            if (task.instruction().length() > 2000) {
                throw new IllegalArgumentException("Workflow plan task instruction is too long");
            }
            sequenceByTaskId.put(task.taskId(), task.sequence());
        }
        if (!usedAgents.equals(allowedAgents)) {
            throw new IllegalArgumentException("Planner must use every allowed target agent exactly once");
        }

        for (WorkflowPlanTask task : plan.tasks()) {
            List<String> dependencies = task.dependsOn() == null ? List.of() : task.dependsOn();
            if (new HashSet<>(dependencies).size() != dependencies.size()) {
                throw new IllegalArgumentException("Planner task dependencies must not contain duplicates");
            }
            for (String dependency : dependencies) {
                Integer dependencySequence = sequenceByTaskId.get(dependency);
                if (dependencySequence == null || dependencySequence >= task.sequence()) {
                    throw new IllegalArgumentException(
                            "Planner dependency must reference an earlier task: " + dependency);
                }
            }
        }
        return plan;
    }
}
