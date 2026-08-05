package com.xxx.insurance.ai.memory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.memory.model.AgentInvocationRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;

/**
 * 基于本地数据库的 Agent 调用流水服务。
 */
@Service
@Profile("local-db")
public class JdbcAgentInvocationService implements AgentInvocationService {

    private final JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper;

    public JdbcAgentInvocationService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(AgentInvocationRecord record) {
        jdbcTemplate.update("""
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
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
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
                toTinyInt(record.outputFormatValid()),
                toJson(record.missingSections()),
                record.status(),
                record.errorCode(),
                record.errorMessage(),
                Timestamp.from(record.createdAt()));
    }

    private String toJson(List<String> missingSections) {
        try {
            return objectMapper.writeValueAsString(missingSections == null ? List.of() : missingSections);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize agent invocation missing sections", ex);
        }
    }

    private Integer toTinyInt(Boolean value) {
        if (value == null) {
            return null;
        }
        return value ? 1 : 0;
    }
}
