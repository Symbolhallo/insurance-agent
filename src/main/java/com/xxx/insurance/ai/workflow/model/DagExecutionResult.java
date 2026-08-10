package com.xxx.insurance.ai.workflow.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 动态 DAG 执行器的汇总结果。
 *
 * @param taskResults 按计划 sequence 排序的任务结果
 * @param successCount 成功任务数
 * @param failedCount 失败任务数
 * @param skippedCount 因依赖失败跳过的任务数
 * @param partialSuccess 是否仅有部分任务成功
 */
@Schema(description = "动态 DAG 执行结果")
public record DagExecutionResult(
        @Schema(description = "按计划顺序排列的任务结果")
        List<AgentTaskExecutionResult> taskResults,

        @Schema(description = "成功任务数")
        int successCount,

        @Schema(description = "失败任务数")
        int failedCount,

        @Schema(description = "依赖失败跳过任务数")
        int skippedCount,

        @Schema(description = "是否仅有部分任务成功")
        boolean partialSuccess) {

    /**
     * 防御性复制任务结果，避免 Graph State 在节点完成后被外部集合修改。
     */
    public DagExecutionResult {
        taskResults = taskResults == null ? List.of() : List.copyOf(taskResults);
    }

    /**
     * 按 sequence 排序任务结果并计算成功、失败、跳过和部分成功统计。
     */
    public static DagExecutionResult from(List<AgentTaskExecutionResult> taskResults) {
        List<AgentTaskExecutionResult> orderedResults = taskResults.stream()
                .sorted(java.util.Comparator.comparingInt(AgentTaskExecutionResult::sequence))
                .toList();
        int successCount = (int) orderedResults.stream()
                .filter(result -> result.status() == AgentTaskStatus.SUCCESS)
                .count();
        int failedCount = (int) orderedResults.stream()
                .filter(result -> result.status() == AgentTaskStatus.FAILED)
                .count();
        int skippedCount = (int) orderedResults.stream()
                .filter(result -> result.status() == AgentTaskStatus.SKIPPED_DEPENDENCY_FAILED)
                .count();
        return new DagExecutionResult(
                orderedResults,
                successCount,
                failedCount,
                skippedCount,
                successCount > 0 && successCount < orderedResults.size());
    }
}
