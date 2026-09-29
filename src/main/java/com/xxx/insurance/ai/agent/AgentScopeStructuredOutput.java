package com.xxx.insurance.ai.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.util.JsonSchemaUtils;
import org.springframework.stereotype.Component;

/**
 * AgentScope 结构化输出边界。
 *
 * <p>使用 AgentScope {@link JsonSchemaUtils} 从 Java 类型生成 JSON Schema，并使用项目统一
 * Jackson 配置完成严格反序列化。它替代 Spring AI BeanOutputConverter，但不替代各业务服务
 * 已有的枚举白名单、数量限制和依赖图等确定性校验。</p>
 */
@Component
public class AgentScopeStructuredOutput {

    private final ObjectMapper objectMapper;

    public AgentScopeStructuredOutput(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 返回可直接注入模型指令的 AgentScope JSON Schema。 */
    public String schema(Class<?> outputType) {
        try {
            return objectMapper.writeValueAsString(JsonSchemaUtils.generateSchemaFromClass(outputType));
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize AgentScope output schema", ex);
        }
    }

    /**
     * 将模型 JSON 严格转换为目标类型。
     *
     * <p>部分 OpenAI-compatible 模型在看到 JSON Schema 后，会错误地把实际数据放进
     * {@code {"type":"object","properties":{...}}} 的 Schema 外壳。本方法只识别这一种明确外壳，
     * 取出 {@code properties} 后仍使用同一个目标类型反序列化；字段完整性、Agent 白名单、任务数量和
     * DAG 依赖等规则继续由目标 Record 及业务 Validator 校验，不把任意嵌套 JSON 当作合法输出。</p>
     */
    public <T> T convert(String content, Class<T> outputType) {
        try {
            JsonNode root = objectMapper.readTree(content);
            JsonNode payload = schemaWrappedPayload(root);
            return objectMapper.treeToValue(payload, outputType);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "AgentScope structured output cannot be converted to " + outputType.getSimpleName(), ex);
        }
    }

    /** 仅解包模型误生成的 object/properties Schema 外壳，普通业务 JSON 保持原样。 */
    private JsonNode schemaWrappedPayload(JsonNode root) {
        if (root != null
                && root.isObject()
                && "object".equals(root.path("type").asText())
                && root.path("properties").isObject()) {
            return root.path("properties");
        }
        return root;
    }
}
