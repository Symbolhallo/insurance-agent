package com.xxx.insurance.ai.memory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.memory.mapper.AgentInvocationMapper;
import com.xxx.insurance.ai.memory.model.AgentInvocationRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 基于 MyBatis 的 Agent 调用流水服务。
 */
@Service
@Profile("local-db")
public class MyBatisAgentInvocationService implements AgentInvocationService {

    private final AgentInvocationMapper agentInvocationMapper;

    private final ObjectMapper objectMapper;

    public MyBatisAgentInvocationService(AgentInvocationMapper agentInvocationMapper, ObjectMapper objectMapper) {
        this.agentInvocationMapper = agentInvocationMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(AgentInvocationRecord record) {
        agentInvocationMapper.insert(AgentInvocationMapper.AgentInvocationWriteRecord.from(
                record,
                toTinyInt(record.outputFormatValid()),
                toJson(record.missingSections())));
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
