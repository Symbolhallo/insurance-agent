package com.xxx.insurance.ai.memory.model;

import java.time.Instant;

/**
 * AI 会话主记录。
 *
 * @param conversationId 会话编号
 * @param userId 用户编号
 * @param customerId 客户编号
 * @param operatorId 操作员编号
 * @param sessionType 会话类型
 * @param agentName 创建或更新会话的智能体名称
 * @param title 会话标题
 * @param status 会话状态
 * @param occurredAt 本次会话事件发生时间
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
