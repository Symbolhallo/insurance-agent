package com.xxx.insurance.ai.memory.service;

import com.xxx.insurance.ai.memory.model.AgentMemoryExchange;
import com.xxx.insurance.ai.memory.model.AgentInvocationRecord;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * Agent 记忆协调服务。
 *
 * <p>该服务负责协调窗口记忆和长期记忆，避免业务 Agent 分别写多张表导致一致性问题。</p>
 */
public interface AgentMemoryService {

    boolean isEnabled();

    List<Message> getHistory(String conversationId);

    void saveSuccessfulExchange(AgentMemoryExchange exchange, AgentInvocationRecord invocationRecord);

    void saveFailedInvocation(AgentInvocationRecord invocationRecord);
}
