package com.xxx.insurance.ai.memory.mapper;

import com.xxx.insurance.ai.memory.model.AgentInvocationRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 调用流水写入 Mapper。
 */
@Mapper
public interface AgentInvocationMapper {

    @Insert("""
            insert into ai_agent_invocation (
                invocation_id,
                conversation_id,
                agent_name,
                trace_id,
                workflow_instance_id,
                workflow_step_id,
                model_provider,
                model_name,
                user_id,
                customer_id,
                operator_id,
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
            ) values (
                #{invocationId},
                #{conversationId},
                #{agentName},
                #{traceId},
                #{workflowInstanceId},
                #{workflowStepId},
                #{modelProvider},
                #{modelName},
                #{userId},
                #{customerId},
                #{operatorId},
                #{userMessage},
                #{assistantAnswer},
                #{durationMs},
                #{answerLength},
                #{outputFormatValid},
                #{missingSectionsJson},
                #{status},
                #{errorCode},
                #{errorMessage},
                #{createdAt}
            )
            """)
    void insert(AgentInvocationWriteRecord record);

    record AgentInvocationWriteRecord(
            String invocationId,
            String conversationId,
            String agentName,
            String traceId,
            String workflowInstanceId,
            String workflowStepId,
            String modelProvider,
            String modelName,
            String userId,
            String customerId,
            String operatorId,
            String userMessage,
            String assistantAnswer,
            Long durationMs,
            Integer answerLength,
            Integer outputFormatValid,
            String missingSectionsJson,
            String status,
            String errorCode,
            String errorMessage,
            java.time.Instant createdAt) {

        public static AgentInvocationWriteRecord from(AgentInvocationRecord record,
                                                      Integer outputFormatValid,
                                                      String missingSectionsJson) {
            return new AgentInvocationWriteRecord(
                    record.invocationId(),
                    record.conversationId(),
                    record.agentName(),
                    record.traceId(),
                    record.workflowInstanceId(),
                    record.workflowStepId(),
                    record.modelProvider(),
                    record.modelName(),
                    record.userId(),
                    record.customerId(),
                    record.operatorId(),
                    record.userMessage(),
                    record.assistantAnswer(),
                    record.durationMs(),
                    record.answerLength(),
                    outputFormatValid,
                    missingSectionsJson,
                    record.status(),
                    record.errorCode(),
                    record.errorMessage(),
                    record.createdAt());
        }
    }
}
