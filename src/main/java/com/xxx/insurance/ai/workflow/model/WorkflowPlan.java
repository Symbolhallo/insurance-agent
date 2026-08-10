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

        @Schema(description = "按稳定序号展示的动态 DAG 任务，执行关系只由 dependsOn 决定")
        List<WorkflowPlanTask> tasks,

        @Schema(description = "规划理由，不包含模型内部思维过程")
        String rationale) {
}
