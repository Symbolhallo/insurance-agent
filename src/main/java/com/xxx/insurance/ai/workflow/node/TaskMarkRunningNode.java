package com.xxx.insurance.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.xxx.insurance.ai.workflow.model.AgentTaskExecutionResult;
import com.xxx.insurance.ai.workflow.model.AgentTaskStatus;
import com.xxx.insurance.ai.workflow.model.WorkflowTaskStateKeys;

import java.time.Instant;
import java.util.Map;

/** 在实际调用子智能体前持久化 RUNNING 状态。 */
public class TaskMarkRunningNode implements NodeAction {

    /** 将 PENDING/READY 快照推进到 RUNNING，恢复时由 Checkpoint 指向下一调用节点。 */
    @Override
    public Map<String, Object> apply(OverAllState state) {
        AgentTaskExecutionResult current = state
                .value(WorkflowTaskStateKeys.TASK_RESULT, AgentTaskExecutionResult.class)
                .orElseThrow(() -> new IllegalStateException("Task graph state has no task result"));
        if (current.terminal()) {
            return Map.of(WorkflowTaskStateKeys.TASK_RESULT, current);
        }
        return Map.of(WorkflowTaskStateKeys.TASK_RESULT, new AgentTaskExecutionResult(
                current.taskId(),
                current.sequence(),
                current.agentName(),
                AgentTaskStatus.RUNNING,
                null,
                null,
                null,
                Instant.now(),
                null,
                0,
                0));
    }
}
