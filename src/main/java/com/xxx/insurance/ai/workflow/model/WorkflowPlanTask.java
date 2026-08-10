package com.xxx.insurance.ai.workflow.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Planner 生成的单个执行任务。
 */
@Schema(description = "工作流计划任务")
public record WorkflowPlanTask(
        @Schema(description = "计划内任务编号", example = "task-1")
        String taskId,

        @Schema(description = "执行顺序，从 1 开始", example = "1")
        int sequence,

        @Schema(description = "目标智能体名称", example = "product-analysis-agent")
        String agentName,

        @Schema(description = "交给目标智能体的任务指令")
        String instruction,

        @Schema(description = "依赖的更早任务编号；无依赖时为空数组")
        List<String> dependsOn) {
}
