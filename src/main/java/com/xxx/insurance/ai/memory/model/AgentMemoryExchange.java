package com.xxx.insurance.ai.memory.model;

import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.UserMessage;
import com.xxx.insurance.common.security.RequestIdentity;

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
 * @param identity 记忆所属租户、用户、客户和操作员
 */
public record AgentMemoryExchange(
        String conversationId,
        String invocationId,
        String agentName,
        UserMessage userMessage,
        AssistantMessage assistantMessage,
        Instant occurredAt,
        RequestIdentity identity) {

    public AgentMemoryExchange(String conversationId,
                               String invocationId,
                               String agentName,
                               UserMessage userMessage,
                               AssistantMessage assistantMessage,
                               Instant occurredAt) {
        this(conversationId, invocationId, agentName, userMessage, assistantMessage,
                occurredAt, RequestIdentity.localDefault());
    }
}
