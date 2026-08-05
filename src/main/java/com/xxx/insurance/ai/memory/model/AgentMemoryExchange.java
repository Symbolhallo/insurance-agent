package com.xxx.insurance.ai.memory.model;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Instant;

/**
 * Agent 成功调用后的记忆交换记录。
 *
 * @param conversationId 会话编号
 * @param invocationId Agent 调用编号
 * @param agentName 智能体名称
 * @param userMessage 用户消息
 * @param assistantMessage 助手消息
 * @param occurredAt 业务事件发生时间
 */
public record AgentMemoryExchange(
        String conversationId,
        String invocationId,
        String agentName,
        UserMessage userMessage,
        AssistantMessage assistantMessage,
        Instant occurredAt) {
}
