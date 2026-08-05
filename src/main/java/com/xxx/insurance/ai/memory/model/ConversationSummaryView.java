package com.xxx.insurance.ai.memory.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 会话摘要查询视图。
 */
@Schema(description = "会话摘要查询视图")
public record ConversationSummaryView(
        @Schema(description = "摘要编号")
        String summaryId,

        @Schema(description = "会话编号")
        String conversationId,

        @Schema(description = "智能体名称")
        String agentName,

        @Schema(description = "摘要内容")
        String summary,

        @Schema(description = "摘要覆盖的起始消息编号")
        String sourceMessageStartId,

        @Schema(description = "摘要覆盖的结束消息编号")
        String sourceMessageEndId,

        @Schema(description = "创建时间")
        Instant createdAt) {
}
