package com.xxx.insurance.ai.memory.model;

/**
 * 会话摘要持久化记录。
 *
 * @param summaryId 摘要编号
 * @param conversationId 所属会话编号
 * @param agentName 生成摘要的智能体名称
 * @param summary 摘要内容
 * @param sourceMessageStartId 摘要覆盖的起始长期记忆编号
 * @param sourceMessageEndId 摘要覆盖的结束长期记忆编号
 */
public record ConversationSummaryRecord(
        String summaryId,
        String conversationId,
        String agentName,
        String summary,
        String sourceMessageStartId,
        String sourceMessageEndId) {
}
