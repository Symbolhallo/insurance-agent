package com.xxx.insurance.ai.memory.model;

/**
 * Spring AI ChatMemory 窗口消息持久化记录。
 */
public record ChatMemoryMessageRecord(
        String messageId,
        String conversationId,
        int messageOrder,
        String messageType,
        String textContent,
        String metadataJson) {
}
