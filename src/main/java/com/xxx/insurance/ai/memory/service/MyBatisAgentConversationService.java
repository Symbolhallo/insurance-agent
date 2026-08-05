package com.xxx.insurance.ai.memory.service;

import com.xxx.insurance.ai.memory.mapper.AgentConversationMapper;
import com.xxx.insurance.ai.memory.model.AgentConversationRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 基于 MyBatis 的 AI 会话主表服务。
 */
@Service
@Profile("local-db")
public class MyBatisAgentConversationService implements AgentConversationService {

    private final AgentConversationMapper agentConversationMapper;

    public MyBatisAgentConversationService(AgentConversationMapper agentConversationMapper) {
        this.agentConversationMapper = agentConversationMapper;
    }

    @Override
    public void upsertActiveConversation(AgentConversationRecord record) {
        agentConversationMapper.upsertActiveConversation(record);
    }
}
