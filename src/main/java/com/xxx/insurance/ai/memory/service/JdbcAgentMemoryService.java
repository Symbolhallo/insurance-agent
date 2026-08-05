package com.xxx.insurance.ai.memory.service;

import com.xxx.insurance.ai.memory.model.AgentMemoryExchange;
import com.xxx.insurance.ai.memory.model.AgentInvocationRecord;
import com.xxx.insurance.ai.memory.model.LongTermMemoryRecord;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 基于本地数据库的 Agent 记忆协调服务。
 *
 * <p>该服务在同一个事务内写入窗口记忆、长期记忆和 Agent 调用流水，保证一次成功
 * Agent 调用对应的 `ai_chat_memory`、`ai_long_term_memory`、`ai_agent_invocation`
 * 变更具备原子性。</p>
 */
@Service
@Profile("local-db")
public class JdbcAgentMemoryService implements AgentMemoryService {

    private final ChatMemory chatMemory;

    private final LongTermMemoryService longTermMemoryService;

    private final AgentInvocationService agentInvocationService;

    public JdbcAgentMemoryService(ChatMemory chatMemory,
                                  LongTermMemoryService longTermMemoryService,
                                  AgentInvocationService agentInvocationService) {
        this.chatMemory = chatMemory;
        this.longTermMemoryService = longTermMemoryService;
        this.agentInvocationService = agentInvocationService;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public List<Message> getHistory(String conversationId) {
        return chatMemory.get(conversationId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveSuccessfulExchange(AgentMemoryExchange exchange, AgentInvocationRecord invocationRecord) {
        chatMemory.add(exchange.conversationId(), List.of(exchange.userMessage(), exchange.assistantMessage()));
        longTermMemoryService.save(toLongTermMemoryRecord(exchange, MessageType.USER, exchange.userMessage().getText()));
        longTermMemoryService.save(toLongTermMemoryRecord(
                exchange,
                MessageType.ASSISTANT,
                exchange.assistantMessage().getText()));
        agentInvocationService.save(invocationRecord);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveFailedInvocation(AgentInvocationRecord invocationRecord) {
        agentInvocationService.save(invocationRecord);
    }

    private LongTermMemoryRecord toLongTermMemoryRecord(AgentMemoryExchange exchange,
                                                        MessageType role,
                                                        String content) {
        return new LongTermMemoryRecord(
                newMemoryId(),
                exchange.conversationId(),
                exchange.invocationId(),
                exchange.agentName(),
                "MESSAGE",
                role,
                content,
                summarize(content),
                "[\"product-analysis\"]",
                BigDecimal.valueOf(50),
                "{\"source\":\"product-analysis-chat\"}",
                exchange.occurredAt());
    }

    private String newMemoryId() {
        return "ltm-" + UUID.randomUUID().toString().replace("-", "");
    }

    private String summarize(String content) {
        if (content == null) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 200) {
            return normalized;
        }
        return normalized.substring(0, 200);
    }
}
