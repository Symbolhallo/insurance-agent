package com.xxx.insurance.ai.memory.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.memory.mapper.ChatMemoryMapper;
import com.xxx.insurance.ai.memory.model.ChatMemoryMessageRecord;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.UserMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 基于 MyBatis 和 OceanBase 的 AgentScope 短期消息窗口仓库。
 *
 * <p>数据库仍保存当前 conversationId 的完整有限窗口，但应用消息类型已经统一为 AgentScope
 * {@link Msg}。覆盖保存始终在事务中执行，先删除旧窗口再按 message_order 插入新窗口；追加
 * 操作先读取当前窗口、裁剪到上限后再覆盖，保持原有并发控制和表结构语义。</p>
 */
@Repository
@Profile("local-db")
public class MyBatisChatMemoryRepository {

    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {
    };

    private final ChatMemoryMapper chatMemoryMapper;

    private final ObjectMapper objectMapper;

    public MyBatisChatMemoryRepository(ChatMemoryMapper chatMemoryMapper, ObjectMapper objectMapper) {
        this.chatMemoryMapper = chatMemoryMapper;
        this.objectMapper = objectMapper;
    }

    /** 返回当前窗口表中的全部会话标识。 */
    public List<String> findConversationIds() {
        return chatMemoryMapper.findConversationIds();
    }

    /** 按数据库顺序恢复 AgentScope 消息及其元数据。 */
    public List<Msg> findByConversationId(String conversationId) {
        return chatMemoryMapper.findByConversationId(conversationId).stream()
                .map(this::toMessage)
                .toList();
    }

    /** 向会话窗口追加消息并裁剪最旧内容。 */
    @Transactional(rollbackFor = Exception.class)
    public void add(String conversationId, List<Msg> messages, int maxMessages) {
        List<Msg> window = new ArrayList<>(findByConversationId(conversationId));
        window.addAll(messages);
        int fromIndex = Math.max(0, window.size() - maxMessages);
        saveAll(conversationId, window.subList(fromIndex, window.size()));
    }

    /** 事务性覆盖保存一个会话的完整 AgentScope 消息窗口。 */
    @Transactional(rollbackFor = Exception.class)
    public void saveAll(String conversationId, List<Msg> messages) {
        deleteByConversationId(conversationId);
        for (int i = 0; i < messages.size(); i++) {
            Msg message = messages.get(i);
            chatMemoryMapper.insert(new ChatMemoryMessageRecord(
                    newMessageId(),
                    conversationId,
                    i,
                    message.getRole().name(),
                    message.getTextContent(),
                    toJson(message.getMetadata())));
        }
    }

    /** 删除会话短期窗口；长期记忆和调用审计不受影响。 */
    public void deleteByConversationId(String conversationId) {
        chatMemoryMapper.deleteByConversationId(conversationId);
    }

    /** 根据持久化 role 还原具体 AgentScope 消息类型。 */
    private Msg toMessage(ChatMemoryMessageRecord record) {
        MsgRole role = MsgRole.valueOf(record.messageType());
        Map<String, Object> metadata = fromJson(record.metadataJson());
        return switch (role) {
            case USER -> UserMessage.builder()
                    .textContent(record.textContent())
                    .metadata(metadata)
                    .build();
            case ASSISTANT -> AssistantMessage.builder()
                    .textContent(record.textContent())
                    .metadata(metadata)
                    .build();
            case SYSTEM -> SystemMessage.builder()
                    .textContent(record.textContent())
                    .metadata(metadata)
                    .build();
            case TOOL -> Msg.builder()
                    .role(MsgRole.TOOL)
                    .textContent(record.textContent())
                    .metadata(metadata)
                    .build();
        };
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize AgentScope memory metadata", ex);
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
            throw new IllegalStateException("Failed to deserialize AgentScope memory metadata", ex);
        }
    }

    private String newMessageId() {
        return "msg-" + UUID.randomUUID().toString().replace("-", "");
    }
}
