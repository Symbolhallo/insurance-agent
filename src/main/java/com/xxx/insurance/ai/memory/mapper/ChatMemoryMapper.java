package com.xxx.insurance.ai.memory.mapper;

import com.xxx.insurance.ai.memory.model.ChatMemoryMessageRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Spring AI ChatMemory 窗口表 Mapper。
 */
@Mapper
public interface ChatMemoryMapper {

    /** 列出当前窗口表中存在的 conversationId，供 Spring AI ChatMemoryRepository 枚举会话。 */
    @Select("""
            select distinct conversation_id
            from ai_chat_memory
            order by conversation_id
            """)
    List<String> findConversationIds();

    /**
     * 按 message_order 升序读取会话完整短期记忆窗口，确保还原给模型的消息顺序稳定。
     */
    @Select("""
            select message_id,
                   conversation_id,
                   message_order,
                   message_type,
                   text_content,
                   metadata_json
            from ai_chat_memory
            where conversation_id = #{conversationId}
            order by message_order asc
            """)
    List<ChatMemoryMessageRecord> findByConversationId(String conversationId);

    /**
     * 插入窗口中的一条消息。该方法由 saveAll 在同一事务内按顺序调用，不承担单条追加语义。
     */
    @Insert("""
            insert into ai_chat_memory (
                message_id,
                conversation_id,
                message_order,
                message_type,
                text_content,
                metadata_json
            ) values (
                #{messageId},
                #{conversationId},
                #{messageOrder},
                #{messageType},
                #{textContent},
                #{metadataJson}
            )
            """)
    void insert(ChatMemoryMessageRecord record);

    /**
     * 删除一个会话当前的短期记忆窗口；saveAll 会在同一事务中紧接着写入完整新窗口。
     */
    @Delete("delete from ai_chat_memory where conversation_id = #{conversationId}")
    void deleteByConversationId(String conversationId);
}
