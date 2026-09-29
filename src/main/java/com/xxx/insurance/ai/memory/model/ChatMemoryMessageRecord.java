package com.xxx.insurance.ai.memory.model;

/**
 * AgentScope 业务会话窗口消息持久化记录。
 *
 * @param messageId 消息编号
 * @param conversationId 所属会话编号
 * @param messageOrder 消息在当前会话窗口中的顺序
 * @param messageType AgentScope 消息角色
 * @param textContent 消息文本内容
 * @param metadataJson 消息扩展元数据 JSON
 */
public record ChatMemoryMessageRecord(
        String messageId,
        String conversationId,
        int messageOrder,
        String messageType,
        String textContent,
        String metadataJson) {
}
