package com.xxx.insurance.ai.memory.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 基于本地数据库的 Spring AI ChatMemoryRepository 实现。
 *
 * <p>该类直接实现 Spring AI 的 {@link ChatMemoryRepository}，而不是自定义一套
 * Memory API。这样上层可以继续使用 {@code ChatMemory -> MessageWindowChatMemory}
 * 的标准抽象，后续接入 ReactAgent 时只需要围绕 conversationId 读取和写入
 * Spring AI {@link Message}。</p>
 *
 * <p>{@link #saveAll(String, List)} 按 Spring AI 语义保存当前会话窗口内的完整消息列表。
 * 因此这里采用“删除会话旧窗口，再按 message_order 插入新窗口”的方式，避免业务侧
 * 关心窗口裁剪和消息覆盖策略。</p>
 */
public class JdbcChatMemoryRepository implements ChatMemoryRepository {

    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper;

    private final TransactionTemplate transactionTemplate;

    public JdbcChatMemoryRepository(JdbcTemplate jdbcTemplate,
                                    ObjectMapper objectMapper,
                                    TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public List<String> findConversationIds() {
        return jdbcTemplate.queryForList("""
                select distinct conversation_id
                from ai_chat_memory
                order by conversation_id
                """, String.class);
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return jdbcTemplate.query("""
                select message_type, text_content, metadata_json
                from ai_chat_memory
                where conversation_id = ?
                order by message_order asc
                """, this::mapMessage, conversationId);
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        transactionTemplate.executeWithoutResult(status -> {
            deleteByConversationId(conversationId);
            for (int i = 0; i < messages.size(); i++) {
                Message message = messages.get(i);
                jdbcTemplate.update("""
                        insert into ai_chat_memory (
                            message_id,
                            conversation_id,
                            message_order,
                            message_type,
                            text_content,
                            metadata_json
                        ) values (?, ?, ?, ?, ?, ?)
                        """,
                        newMessageId(),
                        conversationId,
                        i,
                        message.getMessageType().name(),
                        message.getText(),
                        toJson(message.getMetadata()));
            }
        });
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        jdbcTemplate.update("delete from ai_chat_memory where conversation_id = ?", conversationId);
    }

    private Message mapMessage(ResultSet resultSet, int rowNumber) throws SQLException {
        MessageType messageType = MessageType.valueOf(resultSet.getString("message_type"));
        String textContent = resultSet.getString("text_content");
        Map<String, Object> metadata = fromJson(resultSet.getString("metadata_json"));
        return switch (messageType) {
            case USER -> UserMessage.builder()
                    .text(textContent)
                    .metadata(metadata)
                    .build();
            case ASSISTANT -> AssistantMessage.builder()
                    .content(textContent)
                    .properties(metadata)
                    .build();
            case SYSTEM -> SystemMessage.builder()
                    .text(textContent)
                    .metadata(metadata)
                    .build();
            case TOOL -> ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse("", "", textContent)))
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
