package com.xxx.insurance.ai.workflow.checkpoint.config;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.serializer.StateSerializer;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.xxx.insurance.ai.workflow.checkpoint.GraphCheckpointStateCodec;
import com.xxx.insurance.ai.workflow.checkpoint.OceanBaseCheckpointSaver;
import com.xxx.insurance.ai.workflow.checkpoint.mapper.GraphCheckpointMapper;
import com.xxx.insurance.ai.workflow.model.WorkflowEntity;
import com.xxx.insurance.ai.workflow.model.WorkflowPlanTask;
import com.xxx.insurance.ai.workflow.model.AgentTaskExecutionResult;
import com.xxx.insurance.ai.workflow.model.AgentTaskStatus;
import com.xxx.insurance.ai.workflow.model.DagExecutionResult;
import com.xxx.insurance.ai.workflow.model.SubAgentExecutionResult;
import com.xxx.insurance.product.model.ProductCandidate;
import com.xxx.insurance.product.model.ConfirmedProduct;
import com.xxx.insurance.product.model.ProductRecallResult;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Qualifier;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Main Graph Checkpoint 基础设施配置。
 *
 * <p>StateGraph 与 OceanBase Saver 必须复用同一种 StateSerializer。否则图执行期间可以
 * 生成 Checkpoint，但服务重启后可能因为 Message 或业务 DTO 类型信息不一致而恢复失败。</p>
 */
@Configuration
@EnableConfigurationProperties(GraphCheckpointProperties.class)
public class GraphCheckpointConfig {

    public static final String MAIN_WORKFLOW_STATE_SERIALIZER = "mainWorkflowStateSerializer";

    public static final String MAIN_WORKFLOW_CHECKPOINT_SAVER = "mainWorkflowCheckpointSaver";

    /**
     * 创建 Main Graph 专属状态序列化器。
     *
     * <p>该 Bean 在独立 ObjectMapper 副本上注册工作流 Record 类型，既保证 Checkpoint
     * 恢复后仍是具体 DTO，又不改变 REST API 使用的全局 ObjectMapper。</p>
     *
     * @param objectMapper Spring Boot 全局 ObjectMapper，仅作为复制和基础配置来源
     * @return StateGraph 和 OceanBase Saver 共同使用的 StateSerializer
     */
    @Bean(MAIN_WORKFLOW_STATE_SERIALIZER)
    public StateSerializer mainWorkflowStateSerializer(ObjectMapper objectMapper) {
        ObjectMapper checkpointObjectMapper = objectMapper.copy();

        /*
         * Spring AI Alibaba 1.1.2.3 的 GenericListDeserializer 只能依靠元素上的
         * 类型标记恢复 List 中的具体 DTO。Record 是 final 类型，框架默认的 NON_FINAL
         * 类型策略不会为 WorkflowEntity、WorkflowPlanTask 写入标记，恢复后会退化为 Map。
         * 专用 Module 写入框架能够识别的 @typeHint，并且只注册到 Checkpoint ObjectMapper，
         * 避免污染 REST API 的 JSON 合同。
         */
        SimpleModule workflowStateModule = new SimpleModule("main-workflow-checkpoint-types");
        workflowStateModule.addSerializer(WorkflowEntity.class, new WorkflowEntityCheckpointSerializer());
        workflowStateModule.addSerializer(WorkflowPlanTask.class, new WorkflowPlanTaskCheckpointSerializer());
        workflowStateModule.addSerializer(ProductCandidate.class, new ProductCandidateCheckpointSerializer());
        workflowStateModule.addSerializer(ConfirmedProduct.class, new ConfirmedProductCheckpointSerializer());
        workflowStateModule.addSerializer(ProductRecallResult.class, new ProductRecallResultCheckpointSerializer());
        workflowStateModule.addDeserializer(ProductRecallResult.class, new ProductRecallResultCheckpointDeserializer());
        workflowStateModule.addSerializer(DagExecutionResult.class, new DagExecutionResultCheckpointSerializer());
        workflowStateModule.addDeserializer(DagExecutionResult.class, new DagExecutionResultCheckpointDeserializer());
        checkpointObjectMapper.registerModule(workflowStateModule);
        return new SpringAIJacksonStateSerializer(OverAllState::new, checkpointObjectMapper);
    }

    /**
     * 创建 Checkpoint State 的二进制编解码适配器。
     *
     * @param mainWorkflowStateSerializer 主工作流状态序列化器
     * @return OceanBaseCheckpointSaver 写入和读取状态载荷时使用的 Codec
     */
    @Bean
    @Profile("local-db")
    public GraphCheckpointStateCodec graphCheckpointStateCodec(
            @Qualifier(MAIN_WORKFLOW_STATE_SERIALIZER) StateSerializer mainWorkflowStateSerializer) {
        return new GraphCheckpointStateCodec(mainWorkflowStateSerializer);
    }

    /**
     * 创建 local-db profile 下的 OceanBase CheckpointSaver。
     *
     * @param mapper Checkpoint 和线程记录的 MyBatis Mapper
     * @param stateCodec Graph State 编解码器
     * @param properties Checkpoint 保留期、版本和重试配置
     * @return 注册到 Main Graph CompileConfig 的持久化 Saver
     */
    @Bean(MAIN_WORKFLOW_CHECKPOINT_SAVER)
    @Profile("local-db")
    public OceanBaseCheckpointSaver mainWorkflowCheckpointSaver(GraphCheckpointMapper mapper,
                                                                 GraphCheckpointStateCodec stateCodec,
                                                                 GraphCheckpointProperties properties) {
        return new OceanBaseCheckpointSaver(mapper, stateCodec, properties);
    }

    private static final class WorkflowEntityCheckpointSerializer extends StdSerializer<WorkflowEntity> {

        /** 注册 WorkflowEntity 的 Checkpoint 序列化目标类型。 */
        private WorkflowEntityCheckpointSerializer() {
            super(WorkflowEntity.class);
        }

        /** 写入实体字段和框架恢复列表元素所需的类型标记。 */
        @Override
        public void serialize(WorkflowEntity value,
                              JsonGenerator generator,
                              SerializerProvider provider) throws IOException {
            generator.writeStartObject();
            generator.writeStringField("@typeHint", WorkflowEntity.class.getName());
            generator.writeStringField("type", value.type());
            generator.writeStringField("value", value.value());
            generator.writeStringField("source", value.source());
            generator.writeEndObject();
        }
    }

    private static final class WorkflowPlanTaskCheckpointSerializer extends StdSerializer<WorkflowPlanTask> {

        /** 注册 WorkflowPlanTask 的 Checkpoint 序列化目标类型。 */
        private WorkflowPlanTaskCheckpointSerializer() {
            super(WorkflowPlanTask.class);
        }

        /** 写入 Planner 任务、依赖列表和类型标记。 */
        @Override
        public void serialize(WorkflowPlanTask value,
                              JsonGenerator generator,
                              SerializerProvider provider) throws IOException {
            generator.writeStartObject();
            generator.writeStringField("@typeHint", WorkflowPlanTask.class.getName());
            generator.writeStringField("taskId", value.taskId());
            generator.writeNumberField("sequence", value.sequence());
            generator.writeStringField("agentName", value.agentName());
            generator.writeStringField("instruction", value.instruction());
            generator.writeObjectField("dependsOn", value.dependsOn());
            generator.writeEndObject();
        }
    }

    private static final class ProductCandidateCheckpointSerializer extends StdSerializer<ProductCandidate> {

        /** 注册 ProductCandidate 的 Checkpoint 序列化目标类型。 */
        private ProductCandidateCheckpointSerializer() {
            super(ProductCandidate.class);
        }

        /** 写入产品候选字段和类型标记。 */
        @Override
        public void serialize(ProductCandidate value,
                              JsonGenerator generator,
                              SerializerProvider provider) throws IOException {
            generator.writeStartObject();
            generator.writeStringField("@typeHint", ProductCandidate.class.getName());
            generator.writeStringField("productCode", value.productCode());
            generator.writeStringField("productName", value.productName());
            generator.writeStringField("productType", value.productType());
            generator.writeStringField("insurerName", value.insurerName());
            generator.writeStringField("score", value.score().toPlainString());
            generator.writeStringField("matchReason", value.matchReason());
            generator.writeEndObject();
        }
    }

    private static final class ConfirmedProductCheckpointSerializer extends StdSerializer<ConfirmedProduct> {

        /** 注册 ConfirmedProduct 的 Checkpoint 序列化目标类型。 */
        private ConfirmedProductCheckpointSerializer() {
            super(ConfirmedProduct.class);
        }

        /** 写入会话范围内的标准确认产品信息。 */
        @Override
        public void serialize(ConfirmedProduct value,
                              JsonGenerator generator,
                              SerializerProvider provider) throws IOException {
            generator.writeStartObject();
            generator.writeStringField("@typeHint", ConfirmedProduct.class.getName());
            generator.writeStringField("conversationId", value.conversationId());
            generator.writeStringField("productCode", value.productCode());
            generator.writeStringField("productName", value.productName());
            generator.writeStringField("productType", value.productType());
            generator.writeStringField("insurerName", value.insurerName());
            generator.writeStringField("sourceClue", value.sourceClue());
            generator.writeStringField("retrievalCallId", value.retrievalCallId());
            generator.writeStringField("workflowInstanceId", value.workflowInstanceId());
            generator.writeStringField("confirmedAt", value.confirmedAt().toString());
            generator.writeEndObject();
        }

        /** 在框架请求带类型序列化时复用自定义类型标记格式。 */
        @Override
        public void serializeWithType(ConfirmedProduct value,
                                      JsonGenerator generator,
                                      SerializerProvider provider,
                                      TypeSerializer typeSerializer) throws IOException {
            // resolvedProducts 会作为顶层 State 列表持久化，元素自身的 @typeHint 用于恢复具体 Record 类型。
            serialize(value, generator, provider);
        }
    }

    private static final class ProductRecallResultCheckpointSerializer extends StdSerializer<ProductRecallResult> {

        /** 注册 ProductRecallResult 的 Checkpoint 序列化目标类型。 */
        private ProductRecallResultCheckpointSerializer() {
            super(ProductRecallResult.class);
        }

        /** 写入召回元数据和具有类型标记的候选产品列表。 */
        @Override
        public void serialize(ProductRecallResult value,
                              JsonGenerator generator,
                              SerializerProvider provider) throws IOException {
            generator.writeStartObject();
            generator.writeStringField("@typeHint", ProductRecallResult.class.getName());
            generator.writeStringField("retrievalCallId", value.retrievalCallId());
            generator.writeStringField("query", value.query());
            generator.writeNumberField("topK", value.topK());
            generator.writeArrayFieldStart("candidates");
            ProductCandidateCheckpointSerializer candidateSerializer = new ProductCandidateCheckpointSerializer();
            for (ProductCandidate candidate : value.candidates()) {
                candidateSerializer.serialize(candidate, generator, provider);
            }
            generator.writeEndArray();
            generator.writeBooleanField("mockData", value.mockData());
            generator.writeNumberField("durationMs", value.durationMs());
            generator.writeStringField("recalledAt", value.recalledAt().toString());
            generator.writeEndObject();
        }

        /** 在框架请求带类型序列化时复用自定义类型标记格式。 */
        @Override
        public void serializeWithType(ProductRecallResult value,
                                      JsonGenerator generator,
                                      SerializerProvider provider,
                                      TypeSerializer typeSerializer) throws IOException {
            // 自定义载荷已经写入 @typeHint，避免默认类型处理额外包裹 Record。
            serialize(value, generator, provider);
        }
    }

    private static final class ProductRecallResultCheckpointDeserializer extends StdDeserializer<ProductRecallResult> {

        /** 注册 ProductRecallResult 的 Checkpoint 反序列化目标类型。 */
        private ProductRecallResultCheckpointDeserializer() {
            super(ProductRecallResult.class);
        }

        /** 将 Checkpoint JSON 恢复为召回结果及具体 ProductCandidate 元素。 */
        @Override
        public ProductRecallResult deserialize(JsonParser parser,
                                               DeserializationContext context) throws IOException {
            JsonNode root = parser.getCodec().readTree(parser);
            List<ProductCandidate> candidates = new ArrayList<>();
            for (JsonNode candidate : root.path("candidates")) {
                candidates.add(new ProductCandidate(
                        candidate.path("productCode").asText(),
                        candidate.path("productName").asText(),
                        candidate.path("productType").asText(),
                        candidate.path("insurerName").asText(),
                        new BigDecimal(candidate.path("score").asText()),
                        candidate.path("matchReason").asText()));
            }
            return new ProductRecallResult(
                    root.path("retrievalCallId").asText(),
                    root.path("query").asText(),
                    root.path("topK").asInt(),
                    List.copyOf(candidates),
                    root.path("mockData").asBoolean(),
                    root.path("durationMs").asLong(),
                    Instant.parse(root.path("recalledAt").asText()));
        }
    }

    private static final class DagExecutionResultCheckpointSerializer extends StdSerializer<DagExecutionResult> {

        /** 注册 DagExecutionResult 的 Checkpoint 序列化目标类型。 */
        private DagExecutionResultCheckpointSerializer() {
            super(DagExecutionResult.class);
        }

        /** 写入每个 DAG 任务终态、统一 Agent 响应和汇总计数。 */
        @Override
        public void serialize(DagExecutionResult value,
                              JsonGenerator generator,
                              SerializerProvider provider) throws IOException {
            generator.writeStartObject();
            generator.writeStringField("@typeHint", DagExecutionResult.class.getName());
            generator.writeArrayFieldStart("taskResults");
            for (AgentTaskExecutionResult task : value.taskResults()) {
                generator.writeStartObject();
                generator.writeStringField("taskId", task.taskId());
                generator.writeNumberField("sequence", task.sequence());
                generator.writeStringField("agentName", task.agentName());
                generator.writeStringField("status", task.status().name());
                generator.writeObjectField("response", task.response());
                generator.writeStringField("errorCode", task.errorCode());
                generator.writeStringField("errorMessage", task.errorMessage());
                generator.writeStringField("startedAt", task.startedAt().toString());
                generator.writeStringField("endedAt", task.endedAt().toString());
                generator.writeNumberField("durationMs", task.durationMs());
                generator.writeEndObject();
            }
            generator.writeEndArray();
            generator.writeNumberField("successCount", value.successCount());
            generator.writeNumberField("failedCount", value.failedCount());
            generator.writeNumberField("skippedCount", value.skippedCount());
            generator.writeBooleanField("partialSuccess", value.partialSuccess());
            generator.writeEndObject();
        }

        /** 在框架请求带类型序列化时复用自定义类型标记格式。 */
        @Override
        public void serializeWithType(DagExecutionResult value,
                                      JsonGenerator generator,
                                      SerializerProvider provider,
                                      TypeSerializer typeSerializer) throws IOException {
            serialize(value, generator, provider);
        }
    }

    private static final class DagExecutionResultCheckpointDeserializer extends StdDeserializer<DagExecutionResult> {

        /** 注册 DagExecutionResult 的 Checkpoint 反序列化目标类型。 */
        private DagExecutionResultCheckpointDeserializer() {
            super(DagExecutionResult.class);
        }

        /** 将 Checkpoint JSON 恢复为具体 DAG 任务结果和子智能体响应类型。 */
        @Override
        public DagExecutionResult deserialize(JsonParser parser,
                                              DeserializationContext context) throws IOException {
            JsonNode root = parser.getCodec().readTree(parser);
            List<AgentTaskExecutionResult> taskResults = new ArrayList<>();
            ObjectMapper mapper = (ObjectMapper) parser.getCodec();
            for (JsonNode task : root.path("taskResults")) {
                JsonNode responseNode = task.path("response");
                SubAgentExecutionResult response = responseNode.isMissingNode() || responseNode.isNull()
                        ? null
                        : mapper.treeToValue(responseNode, SubAgentExecutionResult.class);
                taskResults.add(new AgentTaskExecutionResult(
                        task.path("taskId").asText(),
                        task.path("sequence").asInt(),
                        task.path("agentName").asText(),
                        AgentTaskStatus.valueOf(task.path("status").asText()),
                        response,
                        nullableText(task.path("errorCode")),
                        nullableText(task.path("errorMessage")),
                        Instant.parse(task.path("startedAt").asText()),
                        Instant.parse(task.path("endedAt").asText()),
                        task.path("durationMs").asLong()));
            }
            return new DagExecutionResult(
                    taskResults,
                    root.path("successCount").asInt(),
                    root.path("failedCount").asInt(),
                    root.path("skippedCount").asInt(),
                    root.path("partialSuccess").asBoolean());
        }

        /** 将 JSON null 或缺失字段转换为 Java null。 */
        private String nullableText(JsonNode node) {
            return node.isMissingNode() || node.isNull() ? null : node.asText();
        }
    }
}
