package com.xxx.insurance.ai.retrieval.mapper;

import com.xxx.insurance.ai.retrieval.model.RetrievalCallRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 外部召回调用审计 Mapper。
 */
@Mapper
public interface RetrievalCallMapper {

    @Insert("""
            insert into ai_retrieval_call (
                retrieval_call_id,
                conversation_id,
                invocation_id,
                workflow_instance_id,
                domain,
                query_text,
                top_k,
                filters_json,
                result_json,
                duration_ms,
                status,
                error_message,
                created_at
            ) values (
                #{retrievalCallId},
                #{conversationId},
                #{invocationId},
                #{workflowInstanceId},
                #{domain},
                #{queryText},
                #{topK},
                #{filtersJson},
                #{resultJson},
                #{durationMs},
                #{status},
                #{errorMessage},
                #{createdAt}
            )
            """)
    void insert(RetrievalCallRecord record);
}
