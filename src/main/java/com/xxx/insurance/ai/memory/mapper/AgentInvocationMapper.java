package com.xxx.insurance.ai.memory.mapper;

import com.xxx.insurance.ai.memory.model.AgentInvocationRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 调用流水写入 Mapper。
 */
@Mapper
public interface AgentInvocationMapper {

    /**
     * 追加一条 Agent 调用审计，记录输入输出、模型、耗时、格式校验和错误信息。
     * invocationId 是幂等键；主工作流最终调用使用确定性 ID，防止收口重试重复写入。
     */
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

        /** 将领域记录转换为适合 MyBatis/JDBC 标量绑定的写入结构。 */
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
