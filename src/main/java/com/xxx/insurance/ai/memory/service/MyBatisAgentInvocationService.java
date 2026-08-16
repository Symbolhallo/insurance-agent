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

    /** 创建调用审计服务，使用 ObjectMapper 将集合字段转换为 JSON 后交给 MyBatis 写入。 */
    public MyBatisAgentInvocationService(AgentInvocationMapper agentInvocationMapper, ObjectMapper objectMapper) {
        this.agentInvocationMapper = agentInvocationMapper;
        this.objectMapper = objectMapper;
    }

    /** 将格式校验布尔值和缺失章节转换为数据库类型，并追加一条调用审计。 */
    @Override
    public void save(AgentInvocationRecord record) {
        agentInvocationMapper.insert(AgentInvocationMapper.AgentInvocationWriteRecord.from(
                record,
                toTinyInt(record.outputFormatValid()),
                toJson(record.missingSections())));
    }

    /** 将缺失输出章节序列化为 JSON；失败时抛异常并由上层事务整体回滚。 */
    private String toJson(List<String> missingSections) {
        try {
            return objectMapper.writeValueAsString(missingSections == null ? List.of() : missingSections);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize agent invocation missing sections", ex);
        }
    }

    /** 将可空 Boolean 转换为 OceanBase/MySQL tinyint 值。 */
    private Integer toTinyInt(Boolean value) {
        if (value == null) {
            return null;
        }
        return value ? 1 : 0;
    }
}
