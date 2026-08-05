package com.xxx.insurance.ai.memory.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 会话记忆快照查询结果。
 */
@Schema(description = "会话记忆快照查询结果")
public record ConversationMemorySnapshot(
        @Schema(description = "当前是否启用本地数据库记忆")
        boolean memoryEnabled,

        @Schema(description = "会话编号")
        String conversationId,

        @Schema(description = "会话主记录")
        AgentConversationRecord conversation,

        @Schema(description = "窗口记忆消息")
        List<ChatMemoryMessageView> chatMessages,

        @Schema(description = "长期记忆")
        List<LongTermMemoryView> longTermMemories,

        @Schema(description = "会话摘要")
        List<ConversationSummaryView> summaries,

        @Schema(description = "Agent 调用流水")
        List<AgentInvocationView> invocations) {
}
