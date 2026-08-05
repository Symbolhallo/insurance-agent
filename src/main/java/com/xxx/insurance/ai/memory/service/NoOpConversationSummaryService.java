package com.xxx.insurance.ai.memory.service;

import com.xxx.insurance.ai.memory.model.ConversationSummaryResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 默认会话摘要服务。
 */
@Service
@Profile("!local-db")
public class NoOpConversationSummaryService implements ConversationSummaryService {

    @Override
    public ConversationSummaryResponse summarize(String conversationId, int maxMemories) {
        return new ConversationSummaryResponse(false, false, conversationId, null, null, null, 0, 0, Instant.now());
    }
}
