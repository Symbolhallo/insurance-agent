package com.xxx.insurance.ai.workflow.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Main Graph 对不同子智能体响应的统一投影。
 *
 * @param agentName 实际执行的子智能体名称
 * @param conversationId 会话编号
 * @param invocationId 子智能体调用编号
 * @param answer 子智能体回答
 * @param modelInvoked 是否调用模型
 * @param durationMs 执行耗时
 * @param answeredAt 回答时间
 * @param answerLength 回答字符数
 * @param memoryEnabled 是否启用记忆
 * @param memoryMessageCount 携带的历史消息数
 */
@Schema(description = "子智能体统一执行结果")
public record SubAgentExecutionResult(
        @Schema(description = "实际执行的子智能体名称")
        String agentName,

        @Schema(description = "会话编号")
        String conversationId,

        @Schema(description = "子智能体调用编号")
        String invocationId,

        @Schema(description = "子智能体回答")
        String answer,

        @Schema(description = "是否调用模型")
        boolean modelInvoked,

        @Schema(description = "执行耗时，单位毫秒")
        long durationMs,

        @Schema(description = "回答时间")
        Instant answeredAt,

        @Schema(description = "回答字符数")
        int answerLength,

        @Schema(description = "是否启用记忆")
        boolean memoryEnabled,

        @Schema(description = "携带的历史消息数")
        int memoryMessageCount) {
}
