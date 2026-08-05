package com.xxx.insurance.ai.memory.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 会话摘要生成响应。
 */
@Schema(description = "会话摘要生成响应")
public record ConversationSummaryResponse(
        @Schema(description = "当前是否启用本地数据库记忆")
        boolean memoryEnabled,

        @Schema(description = "是否实际调用模型生成摘要")
        boolean modelInvoked,

        @Schema(description = "会话编号")
        String conversationId,

        @Schema(description = "摘要编号")
        String summaryId,

        @Schema(description = "智能体名称")
        String agentName,

        @Schema(description = "摘要内容")
        String summary,

        @Schema(description = "本次摘要使用的长期记忆条数")
        int sourceMemoryCount,

        @Schema(description = "模型调用耗时，单位毫秒")
        long durationMs,

        @Schema(description = "摘要生成时间")
        Instant summarizedAt) {
}
