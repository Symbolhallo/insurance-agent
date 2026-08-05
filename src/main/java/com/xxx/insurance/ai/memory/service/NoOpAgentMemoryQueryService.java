package com.xxx.insurance.ai.memory.service;

import com.xxx.insurance.ai.memory.model.ConversationMemorySnapshot;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 默认记忆查询服务。
 */
@Service
@Profile("!local-db")
public class NoOpAgentMemoryQueryService implements AgentMemoryQueryService {

    @Override
    public ConversationMemorySnapshot getConversationSnapshot(String conversationId, int limit) {
        return new ConversationMemorySnapshot(false, conversationId, null, List.of(), List.of(), List.of(), List.of());
    }
}
