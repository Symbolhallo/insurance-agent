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
 * @param attempts 实际调用子智能体的次数
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
        long durationMs,

        @Schema(description = "实际调用子智能体的次数")
        int attempts) {

    /** 兼容已有终态构造代码；成功和失败默认视为执行过一次，跳过任务为零次。 */
    public AgentTaskExecutionResult(String taskId,
                                    int sequence,
                                    String agentName,
                                    AgentTaskStatus status,
                                    SubAgentExecutionResult response,
                                    String errorCode,
                                    String errorMessage,
                                    Instant startedAt,
                                    Instant endedAt,
                                    long durationMs) {
        this(taskId, sequence, agentName, status, response, errorCode, errorMessage,
                startedAt, endedAt, durationMs,
                status == AgentTaskStatus.SKIPPED_DEPENDENCY_FAILED ? 0 : 1);
    }

    /** 判断当前状态是否已经不可再调度。 */
    public boolean terminal() {
        return status == AgentTaskStatus.SUCCESS
                || status == AgentTaskStatus.FAILED
                || status == AgentTaskStatus.SKIPPED_DEPENDENCY_FAILED;
    }
}
