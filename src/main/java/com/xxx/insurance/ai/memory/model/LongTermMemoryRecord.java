package com.xxx.insurance.ai.memory.model;

import org.springframework.ai.chat.messages.MessageType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 长期记忆写入记录。
 *
 * @param memoryId 长期记忆编号
 * @param conversationId 会话编号
 * @param invocationId Agent 调用编号
 * @param agentName 智能体名称
 * @param memoryType 记忆类型
 * @param role 消息角色
 * @param content 原文内容
 * @param summary 摘要
 * @param tagsJson 标签 JSON
 * @param importanceScore 重要性评分
 * @param metadataJson 扩展元数据 JSON
 * @param occurredAt 业务事件发生时间
 */
public record LongTermMemoryRecord(
        String memoryId,
        String conversationId,
        String invocationId,
        String agentName,
        String memoryType,
        MessageType role,
        String content,
        String summary,
        String tagsJson,
        BigDecimal importanceScore,
        String metadataJson,
        Instant occurredAt) {
}
