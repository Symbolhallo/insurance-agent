package com.xxx.insurance.ai.memory.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 历史会话列表项。
 *
 * @param conversationId 会话编号
 * @param title 会话标题
 * @param agentName 最近归属的智能体名称
 * @param messageCount 已持久化的长期消息数量
 * @param updatedAt 会话最近更新时间
 */
@Schema(description = "历史会话列表项")
public record ConversationListItem(
        @Schema(description = "会话编号")
        String conversationId,

        @Schema(description = "会话标题")
        String title,

        @Schema(description = "最近归属的智能体名称")
        String agentName,

        @Schema(description = "长期消息数量")
        long messageCount,

        @Schema(description = "最近更新时间")
        Instant updatedAt) {
}
