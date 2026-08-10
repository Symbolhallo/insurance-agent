package com.xxx.insurance.ai.workflow.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Planner Agent 输出的结构化执行计划。
 */
@Schema(description = "工作流结构化执行计划")
public record WorkflowPlan(
        @Schema(description = "本次计划要完成的业务目标")
        String objective,

        @Schema(description = "按顺序编号的执行任务；Planner v2 允许一到两个任务")
        List<WorkflowPlanTask> tasks,

        @Schema(description = "规划理由，不包含模型内部思维过程")
        String rationale) {
}
