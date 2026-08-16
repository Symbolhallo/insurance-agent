package com.xxx.insurance.ai.memory.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.memory.mapper.ChatMemoryMapper;
import com.xxx.insurance.ai.memory.model.ChatMemoryMessageRecord;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 基于 MyBatis 的 Spring AI ChatMemoryRepository 实现。
 *
 * <p>该类仍然实现 Spring AI 原生 {@link ChatMemoryRepository}，上层继续使用
 * {@code ChatMemory -> MessageWindowChatMemory} 标准抽象。底层通过 MyBatis Mapper
 * 访问 `ai_chat_memory`，以便与项目内其他本地数据库访问方式保持一致。</p>
 *
 * <p>{@link #saveAll(String, List)} 必须保持 Spring AI 语义：入参 messages 是当前
 * conversationId 的完整窗口消息列表。因此这里仍采用“删除旧窗口，再按 message_order
 * 插入新窗口”的覆盖保存策略。</p>
 */
public class MyBatisChatMemoryRepository implements ChatMemoryRepository {

    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {
    };

    private final ChatMemoryMapper chatMemoryMapper;

    private final ObjectMapper objectMapper;

    public MyBatisChatMemoryRepository(ChatMemoryMapper chatMemoryMapper, ObjectMapper objectMapper) {
        this.chatMemoryMapper = chatMemoryMapper;
        this.objectMapper = objectMapper;
    }

    /** 返回当前短期窗口表中的全部会话标识。 */
    @Override
    public List<String> findConversationIds() {
        return chatMemoryMapper.findConversationIds();
    }

    /** 从数据库按顺序恢复消息，并还原为 Spring AI 对应的 Message 子类型。 */
    @Override
    public List<Message> findByConversationId(String conversationId) {
        return chatMemoryMapper.findByConversationId(conversationId).stream()
                .map(this::toMessage)
                .toList();
    }

    /**
     * 事务性覆盖保存一个会话的完整窗口：先删除旧窗口，再按新列表顺序写入。
     * 任意序列化或插入异常都会回滚删除，避免数据库只留下半个上下文窗口。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveAll(String conversationId, List<Message> messages) {
        deleteByConversationId(conversationId);
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            chatMemoryMapper.insert(new ChatMemoryMessageRecord(
                    newMessageId(),
                    conversationId,
                    i,
                    message.getMessageType().name(),
                    message.getText(),
                    toJson(message.getMetadata())));
        }
    }

    /** 删除会话短期窗口；长期记忆和调用审计不受影响。 */
    @Override
    public void deleteByConversationId(String conversationId) {
        chatMemoryMapper.deleteByConversationId(conversationId);
    }

    private Message toMessage(ChatMemoryMessageRecord record) {
        MessageType messageType = MessageType.valueOf(record.messageType());
        Map<String, Object> metadata = fromJson(record.metadataJson());
        return switch (messageType) {
            case USER -> UserMessage.builder()
                    .text(record.textContent())
                    .metadata(metadata)
                    .build();
            case ASSISTANT -> AssistantMessage.builder()
                    .content(record.textContent())
                    .properties(metadata)
                    .build();
            case SYSTEM -> SystemMessage.builder()
                    .text(record.textContent())
                    .metadata(metadata)
                    .build();
            case TOOL -> ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse("", "", record.textContent())))
                    .metadata(metadata)
                    .build();
        };
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize chat memory metadata", ex);
        }
    }

    private Map<String, Object> fromJson(String metadataJson) {
        if (!StringUtils.hasText(metadataJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metadataJson, METADATA_TYPE);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize chat memory metadata", ex);
        }
    }

    private String newMessageId() {
        return "msg-" + UUID.randomUUID().toString().replace("-", "");
    }
}
