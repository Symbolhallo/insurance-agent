package com.xxx.insurance.ai.memory.model;

/**
 * 会话摘要持久化记录。
 */
public record ConversationSummaryRecord(
        String summaryId,
        String conversationId,
        String agentName,
        String summary,
        String sourceMessageStartId,
        String sourceMessageEndId) {
}
