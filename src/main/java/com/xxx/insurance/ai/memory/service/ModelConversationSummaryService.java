package com.xxx.insurance.ai.memory.service;

import com.xxx.insurance.ai.memory.mapper.AgentMemoryQueryMapper;
import com.xxx.insurance.ai.memory.mapper.ConversationSummaryMapper;
import com.xxx.insurance.ai.memory.model.AgentConversationRecord;
import com.xxx.insurance.ai.memory.model.ConversationSummaryRecord;
import com.xxx.insurance.ai.memory.model.ConversationSummaryResponse;
import com.xxx.insurance.ai.memory.model.LongTermMemoryView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 基于模型调用的会话摘要服务。
 *
 * <p>本服务只在 {@code local-db} profile 下启用。摘要素材来自长期记忆表
 * `ai_long_term_memory`，而不是窗口记忆表 `ai_chat_memory`。这样即使窗口记忆被
 * {@code MessageWindowChatMemory} 裁剪，也可以基于永久历史生成摘要。</p>
 *
 * <p>摘要生成使用全局 {@link ChatModel} Bean，保持当前单模型模式。后续升级为
 * Model Router 后，本服务可改为依赖 Router，由 Router 根据摘要任务选择低成本模型。</p>
 */
@Service
@Profile("local-db")
public class ModelConversationSummaryService implements ConversationSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ModelConversationSummaryService.class);

    private static final int DEFAULT_MAX_MEMORIES = 100;

    private static final int MAX_MEMORIES = 200;

    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是金融保险智能体平台的会话摘要助手，负责把历史对话压缩成可供后续智能体读取的结构化摘要。

            规则：
            - 只能总结输入中出现过的信息，不得补充、猜测或编造保险产品条款；
            - 不承诺收益；
            - 不替代人工投顾、核保、法务或合规审查；
            - 对用户诉求、已讨论产品、明确事实、待补充信息和风险提示分开描述；
            - 如果历史信息不足，明确写出“当前历史信息不足”；
            - 输出使用 Markdown，必须包含以下小标题：
              ## 用户诉求
              ## 已确认事实
              ## 产品讨论
              ## 风险与限制
              ## 待补充信息
            """;

    private final ChatModel chatModel;

    private final AgentMemoryQueryMapper agentMemoryQueryMapper;

    private final ConversationSummaryMapper conversationSummaryMapper;

    public ModelConversationSummaryService(ChatModel chatModel,
                                           AgentMemoryQueryMapper agentMemoryQueryMapper,
                                           ConversationSummaryMapper conversationSummaryMapper) {
        this.chatModel = chatModel;
        this.agentMemoryQueryMapper = agentMemoryQueryMapper;
        this.conversationSummaryMapper = conversationSummaryMapper;
    }

    @Override
    public ConversationSummaryResponse summarize(String conversationId, int maxMemories) {
        int queryLimit = normalizeMaxMemories(maxMemories);
        AgentConversationRecord conversation = agentMemoryQueryMapper.findConversation(conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("conversation does not exist: " + conversationId);
        }
        List<LongTermMemoryView> memories = agentMemoryQueryMapper.findLongTermMemoriesForSummary(
                conversationId,
                queryLimit);
        if (memories.isEmpty()) {
            throw new IllegalArgumentException("conversation has no long term memory: " + conversationId);
        }

        String summaryId = newSummaryId();
        long startNanos = System.nanoTime();
        log.info("[Memory] action=summarize status=start conversationId={} summaryId={} sourceMemoryCount={}",
                conversationId,
                summaryId,
                memories.size());
        String summary = chatModel.call(
                new SystemMessage(SUMMARY_SYSTEM_PROMPT),
                new UserMessage(buildUserPrompt(conversationId, memories)));
        if (!StringUtils.hasText(summary)) {
            throw new IllegalStateException("Conversation summary model returned blank content");
        }
        long durationMs = elapsedMillis(startNanos);
        Instant summarizedAt = Instant.now();

        conversationSummaryMapper.insert(new ConversationSummaryRecord(
                summaryId,
                conversationId,
                conversation.agentName(),
                summary,
                memories.getFirst().memoryId(),
                memories.getLast().memoryId()));
        log.info("[Memory] action=summarize status=success conversationId={} summaryId={} durationMs={} summaryLength={}",
                conversationId,
                summaryId,
                durationMs,
                summary == null ? 0 : summary.length());
        return new ConversationSummaryResponse(
                true,
                true,
                conversationId,
                summaryId,
                conversation.agentName(),
                summary,
                memories.size(),
                durationMs,
                summarizedAt);
    }

    private String buildUserPrompt(String conversationId, List<LongTermMemoryView> memories) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请基于以下会话长期记忆生成摘要。\n");
        prompt.append("conversationId: ").append(conversationId).append("\n\n");
        prompt.append("历史对话：\n");
        for (LongTermMemoryView memory : memories) {
            prompt.append("- [")
                    .append(memory.occurredAt())
                    .append("] ")
                    .append(memory.role())
                    .append(": ")
                    .append(normalizeContent(memory.content()))
                    .append("\n");
        }
        return prompt.toString();
    }

    private int normalizeMaxMemories(int maxMemories) {
        if (maxMemories <= 0) {
            return DEFAULT_MAX_MEMORIES;
        }
        return Math.min(maxMemories, MAX_MEMORIES);
    }

    private String normalizeContent(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        return content.replaceAll("\\s+", " ").trim();
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private String newSummaryId() {
        return "sum-" + UUID.randomUUID().toString().replace("-", "");
    }
}
