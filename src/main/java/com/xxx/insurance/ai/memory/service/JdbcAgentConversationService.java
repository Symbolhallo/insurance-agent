package com.xxx.insurance.ai.memory.service;

import com.xxx.insurance.ai.memory.model.AgentConversationRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;

/**
 * 基于本地数据库的 AI 会话主表服务。
 */
@Service
@Profile("local-db")
public class JdbcAgentConversationService implements AgentConversationService {

    private final JdbcTemplate jdbcTemplate;

    public JdbcAgentConversationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void upsertActiveConversation(AgentConversationRecord record) {
        jdbcTemplate.update("""
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
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on duplicate key update
                    user_id = values(user_id),
                    customer_id = values(customer_id),
                    operator_id = values(operator_id),
                    session_type = values(session_type),
                    agent_name = values(agent_name),
                    title = coalesce(ai_conversation.title, values(title)),
                    status = 'ACTIVE',
                    updated_at = values(updated_at)
                """,
                record.conversationId(),
                record.userId(),
                record.customerId(),
                record.operatorId(),
                record.sessionType(),
                record.agentName(),
                record.title(),
                record.status(),
                Timestamp.from(record.occurredAt()),
                Timestamp.from(record.occurredAt()));
    }
}
