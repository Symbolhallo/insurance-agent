package com.xxx.insurance.ai.memory.service;

import com.xxx.insurance.ai.memory.model.AgentMemoryExchange;
import com.xxx.insurance.ai.memory.model.AgentConversationRecord;
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
public class LocalDbAgentMemoryService implements AgentMemoryService {

    private final ChatMemory chatMemory;

    private final LongTermMemoryService longTermMemoryService;

    private final AgentInvocationService agentInvocationService;

    private final AgentConversationService agentConversationService;

    /** 创建 local-db 记忆事务门面，组合 Spring AI ChatMemory 与会话、长期记忆、调用流水 MyBatis 端口。 */
    public LocalDbAgentMemoryService(ChatMemory chatMemory,
                                     LongTermMemoryService longTermMemoryService,
                                     AgentInvocationService agentInvocationService,
                                     AgentConversationService agentConversationService) {
        this.chatMemory = chatMemory;
        this.longTermMemoryService = longTermMemoryService;
        this.agentInvocationService = agentInvocationService;
        this.agentConversationService = agentConversationService;
    }

    /** local-db 实现始终启用持久化记忆。 */
    @Override
    public boolean isEnabled() {
        return true;
    }

    /** 从 Spring AI ChatMemory 读取指定会话的窗口消息。 */
    @Override
    public List<Message> getHistory(String conversationId) {
        return chatMemory.get(conversationId);
    }

    /**
     * 在调用方事务（主工作流收口）或本方法新事务中原子保存一次最终问答：先 upsert 会话主记录，按
     * MessageWindowChatMemory 语义更新窗口消息，再分别追加 USER/ASSISTANT 长期历史，最后写 SUCCESS
     * invocation。任一步失败整体回滚，保证短期记忆、长期历史和审计不会出现部分成功。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveSuccessfulExchange(AgentMemoryExchange exchange, AgentInvocationRecord invocationRecord) {
        agentConversationService.upsertActiveConversation(toConversationRecord(invocationRecord));
        chatMemory.add(exchange.conversationId(), List.of(exchange.userMessage(), exchange.assistantMessage()));
        longTermMemoryService.save(toLongTermMemoryRecord(exchange, MessageType.USER, exchange.userMessage().getText()));
        longTermMemoryService.save(toLongTermMemoryRecord(
                exchange,
                MessageType.ASSISTANT,
                exchange.assistantMessage().getText()));
        agentInvocationService.save(invocationRecord);
    }

    /**
     * 原子 upsert 会话主记录并追加 DAG 子智能体成功流水；故意不写 ChatMemory/长期问答，避免多个并行
     * 子任务基于旧窗口执行 delete+insert 时互相覆盖，最终会话仅由 Summary/Review 后的主工作流收口写入。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveSuccessfulInvocation(AgentInvocationRecord invocationRecord) {
        agentConversationService.upsertActiveConversation(toConversationRecord(invocationRecord));
        agentInvocationService.save(invocationRecord);
    }

    /**
     * 原子 upsert 会话主记录并追加 FAILED 调用流水，不向短期或长期记忆写入不存在/不可信的助手回答；
     * 失败审计可供排障，但不会成为后续模型上下文。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveFailedInvocation(AgentInvocationRecord invocationRecord) {
        agentConversationService.upsertActiveConversation(toConversationRecord(invocationRecord));
        agentInvocationService.save(invocationRecord);
    }

    /** 从调用流水提取会话主表的新增或更新字段。 */
    private AgentConversationRecord toConversationRecord(AgentInvocationRecord invocationRecord) {
        return new AgentConversationRecord(
                invocationRecord.conversationId(),
                invocationRecord.userId(),
                invocationRecord.customerId(),
                invocationRecord.operatorId(),
                "INTERNAL_TEST",
                invocationRecord.agentName(),
                toConversationTitle(invocationRecord.userMessage()),
                "ACTIVE",
                invocationRecord.createdAt());
    }

    /** 将一条 Spring AI 消息转换为长期记忆表记录。 */
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
                "[\"agent-chat\"]",
                BigDecimal.valueOf(50),
                "{\"source\":\"workflow-or-agent-chat\",\"agentName\":\""
                        + exchange.agentName() + "\"}",
                exchange.occurredAt());
    }

    /** 生成全局唯一的长期记忆记录编号。 */
    private String newMemoryId() {
        return "ltm-" + UUID.randomUUID().toString().replace("-", "");
    }

    /** 生成最多 200 字符的可检索内容摘要。 */
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

    /** 使用首次用户问题生成简短会话标题。 */
    private String toConversationTitle(String userMessage) {
        if (userMessage == null) {
            return "保险智能体会话";
        }
        String normalized = userMessage.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            return "保险智能体会话";
        }
        if (normalized.length() <= 80) {
            return normalized;
        }
        return normalized.substring(0, 80);
    }
}
