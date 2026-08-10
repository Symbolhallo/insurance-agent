package com.xxx.insurance.ai.memory.model;

/**
 * Spring AI ChatMemory 窗口消息持久化记录。
 *
 * @param messageId 消息编号
 * @param conversationId 所属会话编号
 * @param messageOrder 消息在当前会话窗口中的顺序
 * @param messageType Spring AI 消息类型
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
