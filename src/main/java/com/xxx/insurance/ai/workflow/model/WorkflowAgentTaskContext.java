package com.xxx.insurance.ai.workflow.model;

import com.xxx.insurance.product.model.ConfirmedProduct;

import java.util.List;

/**
 * 单个任务子图能够读取的最小执行上下文。
 *
 * <p>该对象放入 RunnableConfig metadata，不进入持久化 State。恢复时由 Planner 计划和主图
 * Checkpoint 重新构造，避免把完整主工作流 State 无差别传给每个子智能体。</p>
 */
public record WorkflowAgentTaskContext(
        WorkflowPlanTask task,
        String conversationId,
        String workflowInstanceId,
        long executionFenceToken,
        String workflowStepId,
        String originalQuestion,
        List<ConfirmedProduct> confirmedProducts,
        List<AgentTaskExecutionResult> dependencyResults,
        boolean tokenStreamingEnabled) {

    /** 固化集合快照，防止并行任务共享可变集合。 */
    public WorkflowAgentTaskContext {
        confirmedProducts = confirmedProducts == null ? List.of() : List.copyOf(confirmedProducts);
        dependencyResults = dependencyResults == null ? List.of() : List.copyOf(dependencyResults);
    }

    /** 兼容不验证持久化租约的单元测试与独立任务调用。 */
    public WorkflowAgentTaskContext(WorkflowPlanTask task,
                                    String conversationId,
                                    String workflowInstanceId,
                                    String workflowStepId,
                                    String originalQuestion,
                                    List<ConfirmedProduct> confirmedProducts,
                                    List<AgentTaskExecutionResult> dependencyResults,
                                    boolean tokenStreamingEnabled) {
        this(task, conversationId, workflowInstanceId, 1L, workflowStepId, originalQuestion,
                confirmedProducts, dependencyResults, tokenStreamingEnabled);
    }
}
