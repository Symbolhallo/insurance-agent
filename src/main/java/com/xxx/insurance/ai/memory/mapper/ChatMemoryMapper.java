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

    @Select("""
            select distinct conversation_id
            from ai_chat_memory
            order by conversation_id
            """)
    List<String> findConversationIds();

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

    @Delete("delete from ai_chat_memory where conversation_id = #{conversationId}")
    void deleteByConversationId(String conversationId);
}
