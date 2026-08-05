package com.xxx.insurance.ai.memory.service;

import com.xxx.insurance.ai.memory.mapper.AgentMemoryQueryMapper;
import com.xxx.insurance.ai.memory.model.ConversationMemorySnapshot;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 基于 MyBatis 的 Agent 记忆只读查询服务。
 */
@Service
@Profile("local-db")
public class MyBatisAgentMemoryQueryService implements AgentMemoryQueryService {

    private static final int DEFAULT_LIMIT = 50;

    private static final int MAX_LIMIT = 200;

    private final AgentMemoryQueryMapper agentMemoryQueryMapper;

    public MyBatisAgentMemoryQueryService(AgentMemoryQueryMapper agentMemoryQueryMapper) {
        this.agentMemoryQueryMapper = agentMemoryQueryMapper;
    }

    @Override
    public ConversationMemorySnapshot getConversationSnapshot(String conversationId, int limit) {
        int queryLimit = normalizeLimit(limit);
        return new ConversationMemorySnapshot(
                true,
                conversationId,
                agentMemoryQueryMapper.findConversation(conversationId),
                agentMemoryQueryMapper.findChatMessages(conversationId),
                agentMemoryQueryMapper.findLongTermMemories(conversationId, queryLimit),
                agentMemoryQueryMapper.findSummaries(conversationId, queryLimit),
                agentMemoryQueryMapper.findInvocations(conversationId, queryLimit));
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
