package com.xxx.insurance.ai.memory.mapper;

import com.xxx.insurance.ai.memory.model.ConversationSummaryRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话摘要写入 Mapper。
 */
@Mapper
public interface ConversationSummaryMapper {

    /**
     * 追加一条由模型生成的会话摘要，并记录其覆盖的起止消息，便于后续追溯摘要来源。
     */
    @Insert("""
            insert into ai_conversation_summary (
                summary_id,
                conversation_id,
                agent_name,
                summary,
                source_message_start_id,
                source_message_end_id
            ) values (
                #{summaryId},
                #{conversationId},
                #{agentName},
                #{summary},
                #{sourceMessageStartId},
                #{sourceMessageEndId}
            )
            """)
    void insert(ConversationSummaryRecord record);
}
