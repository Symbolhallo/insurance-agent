package com.xxx.insurance.ai.memory.service;

import com.xxx.insurance.ai.memory.model.LongTermMemoryRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;

/**
 * 基于本地数据库的长期记忆服务。
 */
@Service
@Profile("local-db")
public class JdbcLongTermMemoryService implements LongTermMemoryService {

    private final JdbcTemplate jdbcTemplate;

    public JdbcLongTermMemoryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(LongTermMemoryRecord record) {
        jdbcTemplate.update("""
                insert into ai_long_term_memory (
                    memory_id,
                    conversation_id,
                    invocation_id,
                    agent_name,
                    memory_type,
                    role,
                    content,
                    summary,
                    tags_json,
                    importance_score,
                    metadata_json,
                    occurred_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.memoryId(),
                record.conversationId(),
                record.invocationId(),
                record.agentName(),
                record.memoryType(),
                record.role().name(),
                record.content(),
                record.summary(),
                record.tagsJson(),
                record.importanceScore(),
                record.metadataJson(),
                Timestamp.from(record.occurredAt()));
    }
}
