package com.xxx.insurance.ai.memory.mapper;

import com.xxx.insurance.ai.memory.model.LongTermMemoryRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 长期记忆写入 Mapper。
 */
@Mapper
public interface LongTermMemoryMapper {

    @Insert("""
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
            ) values (
                #{memoryId},
                #{conversationId},
                #{invocationId},
                #{agentName},
                #{memoryType},
                #{role},
                #{content},
                #{summary},
                #{tagsJson},
                #{importanceScore},
                #{metadataJson},
                #{occurredAt}
            )
            """)
    void insert(LongTermMemoryWriteRecord record);

    record LongTermMemoryWriteRecord(
            String memoryId,
            String conversationId,
            String invocationId,
            String agentName,
            String memoryType,
            String role,
            String content,
            String summary,
            String tagsJson,
            java.math.BigDecimal importanceScore,
            String metadataJson,
            java.time.Instant occurredAt) {

        public static LongTermMemoryWriteRecord from(LongTermMemoryRecord record) {
            return new LongTermMemoryWriteRecord(
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
                    record.occurredAt());
        }
    }
}
