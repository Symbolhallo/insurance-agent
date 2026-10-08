package com.xxx.insurance.ai.workflow.sse.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.workflow.sse.model.WorkflowSseEvent;
import com.xxx.insurance.ai.workflow.sse.model.WorkflowSseEventRecord;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 在数据库事件记录、JSON 载荷和前端 SSE 协议事件之间转换。 */
@Component
public class WorkflowSseEventCodec {

    private static final TypeReference<Map<String, Object>> EVENT_DATA_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public WorkflowSseEventCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 将数据库记录恢复为前端协议事件。 */
    public WorkflowSseEvent toEvent(WorkflowSseEventRecord record) {
        try {
            return new WorkflowSseEvent(
                    record.eventId(), record.eventType(), record.workflowInstanceId(), record.conversationId(),
                    record.nodeCode(), record.sequenceNo(), record.createdAt(),
                    objectMapper.readValue(record.payloadJson(), EVENT_DATA_TYPE));
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize workflow SSE event", ex);
        }
    }

    /** 序列化已脱敏的事件数据。 */
    public String toJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize workflow SSE event", ex);
        }
    }
}
