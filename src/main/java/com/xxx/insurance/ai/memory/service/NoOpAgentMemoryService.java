package com.xxx.insurance.ai.memory.service;

import com.xxx.insurance.ai.memory.model.AgentMemoryExchange;
import org.springframework.ai.chat.messages.Message;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 默认 Agent 记忆服务。
 */
@Service
@Profile("!local-db")
public class NoOpAgentMemoryService implements AgentMemoryService {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public List<Message> getHistory(String conversationId) {
        return List.of();
    }

    @Override
    public void saveSuccessfulExchange(AgentMemoryExchange exchange) {
        // Default profile is intentionally stateless.
    }
}
