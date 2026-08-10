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

        @Schema(description = "目标智能体类型", example = "product-analysis-agent")
        String agentType,

        @Schema(description = "交给目标智能体的任务指令")
        String query,

        @Schema(description = "依赖的更早任务编号；无依赖时为空数组")
        List<String> dependsOn,

        @Schema(description = "子智能体失败后的最大重试次数", example = "1")
        int maxRetries,

        @Schema(description = "该任务是否属于完成用户目标所必需的任务", example = "true")
        boolean required) {

    /** 防御性复制依赖，并拒绝模型输出 null 集合。 */
    public WorkflowPlanTask {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }

    /** 兼容已有固定计划和测试，默认重试一次且任务为必需。 */
    public WorkflowPlanTask(String taskId,
                            int sequence,
                            String agentType,
                            String query,
                            List<String> dependsOn) {
        this(taskId, sequence, agentType, query, dependsOn, 1, true);
    }

    /** 兼容原有业务命名；新代码统一使用 agentType。 */
    public String agentName() {
        return agentType;
    }

    /** 兼容原有业务命名；新代码统一使用 query。 */
    public String instruction() {
        return query;
    }
}
