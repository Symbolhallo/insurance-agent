package com.xxx.insurance.ai.memory.service;

import com.xxx.insurance.ai.memory.model.ConversationSummaryResponse;

/**
 * 会话摘要生成服务。
 */
public interface ConversationSummaryService {

    ConversationSummaryResponse summarize(String conversationId, int maxMemories);
}
