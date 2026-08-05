package com.xxx.insurance.ai.memory.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Agent 调用流水查询视图。
 */
@Schema(description = "Agent 调用流水查询视图")
public record AgentInvocationView(
        @Schema(description = "调用编号")
        String invocationId,

        @Schema(description = "会话编号")
        String conversationId,

        @Schema(description = "智能体名称")
        String agentName,

        @Schema(description = "链路追踪编号")
        String traceId,

        @Schema(description = "模型供应商")
        String modelProvider,

        @Schema(description = "模型名称")
        String modelName,

        @Schema(description = "用户输入")
        String userMessage,

        @Schema(description = "智能体回答")
        String assistantAnswer,

        @Schema(description = "调用耗时，单位毫秒")
        Long durationMs,

        @Schema(description = "回答字符长度")
        Integer answerLength,

        @Schema(description = "输出格式是否有效")
        Boolean outputFormatValid,

        @Schema(description = "缺失输出小标题 JSON")
        String missingSections,

        @Schema(description = "调用状态")
        String status,

        @Schema(description = "错误码")
        String errorCode,

        @Schema(description = "错误信息")
        String errorMessage,

        @Schema(description = "创建时间")
        Instant createdAt) {
}
