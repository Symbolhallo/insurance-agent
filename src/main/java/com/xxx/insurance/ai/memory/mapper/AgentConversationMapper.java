package com.xxx.insurance.ai.memory.mapper;

import com.xxx.insurance.ai.memory.model.AgentConversationRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 会话主表写入 Mapper。
 */
@Mapper
public interface AgentConversationMapper {

    @Insert("""
            insert into ai_conversation (
                conversation_id,
                user_id,
                customer_id,
                operator_id,
                session_type,
                agent_name,
                title,
                status,
                created_at,
                updated_at
            ) values (
                #{conversationId},
                #{userId},
                #{customerId},
                #{operatorId},
                #{sessionType},
                #{agentName},
                #{title},
                #{status},
                #{occurredAt},
                #{occurredAt}
            )
            on duplicate key update
                user_id = values(user_id),
                customer_id = values(customer_id),
                operator_id = values(operator_id),
                session_type = values(session_type),
                agent_name = values(agent_name),
                title = coalesce(ai_conversation.title, values(title)),
                status = 'ACTIVE',
                updated_at = values(updated_at)
            """)
    void upsertActiveConversation(AgentConversationRecord record);
}
