package com.xxx.insurance.ai.memory.model;

import java.time.Instant;

/**
 * AI 会话主记录。
 */
public record AgentConversationRecord(
        String conversationId,
        String userId,
        String customerId,
        String operatorId,
        String sessionType,
        String agentName,
        String title,
        String status,
        Instant occurredAt) {
}
