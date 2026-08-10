package com.xxx.insurance.ai.workflow.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * DAG 中一个计划任务的执行结果。
 *
 * @param taskId 计划任务编号
 * @param sequence 计划内顺序
 * @param agentName 目标子智能体名称
 * @param status 任务终态
 * @param response 子智能体成功响应，失败或跳过时为空
 * @param errorCode 错误编码
 * @param errorMessage 错误说明
 * @param startedAt 开始时间
 * @param endedAt 结束时间
 * @param durationMs 执行耗时，单位毫秒
 */
@Schema(description = "DAG 单任务执行结果")
public record AgentTaskExecutionResult(
        @Schema(description = "计划任务编号", example = "task-1")
        String taskId,

        @Schema(description = "计划内顺序", example = "1")
        int sequence,

        @Schema(description = "目标子智能体名称")
        String agentName,

        @Schema(description = "任务终态")
        AgentTaskStatus status,

        @Schema(description = "子智能体成功响应；失败或跳过时为空")
        SubAgentExecutionResult response,

        @Schema(description = "错误编码")
        String errorCode,

        @Schema(description = "错误说明")
        String errorMessage,

        @Schema(description = "开始时间")
        Instant startedAt,

        @Schema(description = "结束时间")
        Instant endedAt,

        @Schema(description = "执行耗时，单位毫秒")
        long durationMs) {
}
