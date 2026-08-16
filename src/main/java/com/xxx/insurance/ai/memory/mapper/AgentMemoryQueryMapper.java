package com.xxx.insurance.ai.memory.mapper;

import com.xxx.insurance.ai.memory.model.AgentConversationRecord;
import com.xxx.insurance.ai.memory.model.AgentInvocationView;
import com.xxx.insurance.ai.memory.model.ChatMemoryMessageView;
import com.xxx.insurance.ai.memory.model.ConversationSummaryView;
import com.xxx.insurance.ai.memory.model.LongTermMemoryView;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Agent 记忆查询 Mapper。
 *
 * <p>当前 Mapper 只承载 Swagger 观测类只读查询，不参与 Agent 调用链路写入。
 * Agent 调用链路写入由独立写入 Mapper 承载，避免查询和写入 SQL 边界混在一起。</p>
 */
@Mapper
public interface AgentMemoryQueryMapper {

    /**
     * 查询会话主数据及最近更新时间，供管理接口展示会话归属和生命周期状态。
     *
     * @return 会话不存在时返回 {@code null}
     */
    @Select("""
            select conversation_id,
                   user_id,
                   customer_id,
                   operator_id,
                   session_type,
                   agent_name,
                   title,
                   status,
                   updated_at as occurred_at
            from ai_conversation
            where conversation_id = #{conversationId}
            """)
    @ConstructorArgs({
            @Arg(column = "conversation_id", javaType = String.class),
            @Arg(column = "user_id", javaType = String.class),
            @Arg(column = "customer_id", javaType = String.class),
            @Arg(column = "operator_id", javaType = String.class),
            @Arg(column = "session_type", javaType = String.class),
            @Arg(column = "agent_name", javaType = String.class),
            @Arg(column = "title", javaType = String.class),
            @Arg(column = "status", javaType = String.class),
            @Arg(column = "occurred_at", javaType = Instant.class)
    })
    AgentConversationRecord findConversation(@Param("conversationId") String conversationId);

    /** 按模型消费顺序读取当前短期记忆窗口，包含消息元数据和落库时间。 */
    @Select("""
            select message_id,
                   conversation_id,
                   message_order,
                   message_type,
                   text_content,
                   metadata_json,
                   created_at
            from ai_chat_memory
            where conversation_id = #{conversationId}
            order by message_order asc
            """)
    @ConstructorArgs({
            @Arg(column = "message_id", javaType = String.class),
            @Arg(column = "conversation_id", javaType = String.class),
            @Arg(column = "message_order", javaType = int.class),
            @Arg(column = "message_type", javaType = String.class),
            @Arg(column = "text_content", javaType = String.class),
            @Arg(column = "metadata_json", javaType = String.class),
            @Arg(column = "created_at", javaType = Instant.class)
    })
    List<ChatMemoryMessageView> findChatMessages(@Param("conversationId") String conversationId);

    /**
     * 按时间倒序分页读取长期记忆，包括已归档数据，供历史审计和 Swagger 查询。
     */
    @Select("""
            select memory_id,
                   conversation_id,
                   invocation_id,
                   agent_name,
                   memory_type,
                   role,
                   content,
                   summary,
                   tags_json,
                   importance_score,
                   archived,
                   metadata_json,
                   occurred_at,
                   created_at
            from ai_long_term_memory
            where conversation_id = #{conversationId}
            order by occurred_at desc, created_at desc
            limit #{limit}
            """)
    @ConstructorArgs({
            @Arg(column = "memory_id", javaType = String.class),
            @Arg(column = "conversation_id", javaType = String.class),
            @Arg(column = "invocation_id", javaType = String.class),
            @Arg(column = "agent_name", javaType = String.class),
            @Arg(column = "memory_type", javaType = String.class),
            @Arg(column = "role", javaType = String.class),
            @Arg(column = "content", javaType = String.class),
            @Arg(column = "summary", javaType = String.class),
            @Arg(column = "tags_json", javaType = String.class),
            @Arg(column = "importance_score", javaType = BigDecimal.class),
            @Arg(column = "archived", javaType = boolean.class),
            @Arg(column = "metadata_json", javaType = String.class),
            @Arg(column = "occurred_at", javaType = Instant.class),
            @Arg(column = "created_at", javaType = Instant.class)
    })
    List<LongTermMemoryView> findLongTermMemories(@Param("conversationId") String conversationId,
                                                  @Param("limit") int limit);

    /**
     * 按时间正序读取未归档长期记忆，供总结模型构建连续历史；limit 限制单次模型上下文规模。
     */
    @Select("""
            select memory_id,
                   conversation_id,
                   invocation_id,
                   agent_name,
                   memory_type,
                   role,
                   content,
                   summary,
                   tags_json,
                   importance_score,
                   archived,
                   metadata_json,
                   occurred_at,
                   created_at
            from ai_long_term_memory
            where conversation_id = #{conversationId}
              and archived = 0
            order by occurred_at asc, created_at asc
            limit #{limit}
            """)
    @ConstructorArgs({
            @Arg(column = "memory_id", javaType = String.class),
            @Arg(column = "conversation_id", javaType = String.class),
            @Arg(column = "invocation_id", javaType = String.class),
            @Arg(column = "agent_name", javaType = String.class),
            @Arg(column = "memory_type", javaType = String.class),
            @Arg(column = "role", javaType = String.class),
            @Arg(column = "content", javaType = String.class),
            @Arg(column = "summary", javaType = String.class),
            @Arg(column = "tags_json", javaType = String.class),
            @Arg(column = "importance_score", javaType = BigDecimal.class),
            @Arg(column = "archived", javaType = boolean.class),
            @Arg(column = "metadata_json", javaType = String.class),
            @Arg(column = "occurred_at", javaType = Instant.class),
            @Arg(column = "created_at", javaType = Instant.class)
    })
    List<LongTermMemoryView> findLongTermMemoriesForSummary(@Param("conversationId") String conversationId,
                                                            @Param("limit") int limit);

    /** 按生成时间倒序读取会话摘要，供观测接口和后续上下文压缩使用。 */
    @Select("""
            select summary_id,
                   conversation_id,
                   agent_name,
                   summary,
                   source_message_start_id,
                   source_message_end_id,
                   created_at
            from ai_conversation_summary
            where conversation_id = #{conversationId}
            order by created_at desc
            limit #{limit}
            """)
    @ConstructorArgs({
            @Arg(column = "summary_id", javaType = String.class),
            @Arg(column = "conversation_id", javaType = String.class),
            @Arg(column = "agent_name", javaType = String.class),
            @Arg(column = "summary", javaType = String.class),
            @Arg(column = "source_message_start_id", javaType = String.class),
            @Arg(column = "source_message_end_id", javaType = String.class),
            @Arg(column = "created_at", javaType = Instant.class)
    })
    List<ConversationSummaryView> findSummaries(@Param("conversationId") String conversationId,
                                                @Param("limit") int limit);

    /** 按调用时间倒序读取 Agent 调用审计，包含模型、耗时、格式校验及错误信息。 */
    @Select("""
            select invocation_id,
                   conversation_id,
                   agent_name,
                   trace_id,
                   model_provider,
                   model_name,
                   user_message,
                   assistant_answer,
                   duration_ms,
                   answer_length,
                   output_format_valid,
                   missing_sections,
                   status,
                   error_code,
                   error_message,
                   created_at
            from ai_agent_invocation
            where conversation_id = #{conversationId}
            order by created_at desc
            limit #{limit}
            """)
    @ConstructorArgs({
            @Arg(column = "invocation_id", javaType = String.class),
            @Arg(column = "conversation_id", javaType = String.class),
            @Arg(column = "agent_name", javaType = String.class),
            @Arg(column = "trace_id", javaType = String.class),
            @Arg(column = "model_provider", javaType = String.class),
            @Arg(column = "model_name", javaType = String.class),
            @Arg(column = "user_message", javaType = String.class),
            @Arg(column = "assistant_answer", javaType = String.class),
            @Arg(column = "duration_ms", javaType = Long.class),
            @Arg(column = "answer_length", javaType = Integer.class),
            @Arg(column = "output_format_valid", javaType = Boolean.class),
            @Arg(column = "missing_sections", javaType = String.class),
            @Arg(column = "status", javaType = String.class),
            @Arg(column = "error_code", javaType = String.class),
            @Arg(column = "error_message", javaType = String.class),
            @Arg(column = "created_at", javaType = Instant.class)
    })
    List<AgentInvocationView> findInvocations(@Param("conversationId") String conversationId,
                                              @Param("limit") int limit);
}
