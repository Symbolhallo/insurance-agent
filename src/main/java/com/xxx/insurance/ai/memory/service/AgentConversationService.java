package com.xxx.insurance.ai.memory.service;

import com.xxx.insurance.ai.memory.model.AgentConversationRecord;

/**
 * AI 会话主表服务。
 */
public interface AgentConversationService {

    void upsertActiveConversation(AgentConversationRecord record);
}
