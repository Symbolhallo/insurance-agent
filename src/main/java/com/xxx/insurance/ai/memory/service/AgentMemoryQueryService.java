package com.xxx.insurance.ai.memory.service;

import com.xxx.insurance.ai.memory.model.ConversationMemorySnapshot;

/**
 * Agent 记忆只读查询服务。
 */
public interface AgentMemoryQueryService {

    ConversationMemorySnapshot getConversationSnapshot(String conversationId, int limit);
}
