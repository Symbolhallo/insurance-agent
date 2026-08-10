package com.xxx.insurance.ai.memory.service;

import com.xxx.insurance.ai.memory.model.AgentMemoryExchange;
import com.xxx.insurance.ai.memory.model.AgentInvocationRecord;
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

    /** 默认 profile 不启用持久化记忆。 */
    @Override
    public boolean isEnabled() {
        return false;
    }

    /** 默认 profile 返回空历史，保持单轮无状态调用。 */
    @Override
    public List<Message> getHistory(String conversationId) {
        return List.of();
    }

    /** 默认 profile 忽略成功对话保存请求。 */
    @Override
    public void saveSuccessfulExchange(AgentMemoryExchange exchange, AgentInvocationRecord invocationRecord) {
        // Default profile is intentionally stateless.
    }

    /** 默认 profile 忽略成功调用审计请求。 */
    @Override
    public void saveSuccessfulInvocation(AgentInvocationRecord invocationRecord) {
        // Default profile is intentionally stateless.
    }

    /** 默认 profile 忽略失败调用审计请求。 */
    @Override
    public void saveFailedInvocation(AgentInvocationRecord invocationRecord) {
        // Default profile is intentionally stateless.
    }
}
