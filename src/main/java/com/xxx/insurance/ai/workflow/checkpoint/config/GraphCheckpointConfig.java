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
import com.xxx.insurance.ai.workflow.model.OutputReviewDecision;
import com.xxx.insurance.ai.workflow.model.OutputReviewResult;
import com.xxx.insurance.ai.workflow.model.SubAgentExecutionResult;
import com.xxx.insurance.ai.workflow.model.WorkflowSummaryResult;
import com.xxx.insurance.ai.workflow.model.IntentRoute;
import com.xxx.insurance.ai.workflow.model.IntentRoutingResult;
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
import java.util.Map;

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
         * Spring AI Alibaba 1.1.2.0 的 GenericListDeserializer 只能依靠元素上的
         * 类型标记恢复 List 中的具体 DTO。Record 是 final 类型，框架默认的 NON_FINAL
         * 类型策略不会为 WorkflowEntity、WorkflowPlanTask 写入标记，恢复后会退化为 Map。
         * 专用 Module 写入框架能够识别的 @typeHint，并且只注册到 Checkpoint ObjectMapper，
         * 避免污染 REST API 的 JSON 合同。
         */
        SimpleModule workflowStateModule = new SimpleModule("main-workflow-checkpoint-types");
        workflowStateModule.addSerializer(WorkflowEntity.class, new WorkflowEntityCheckpointSerializer());
        workflowStateModule.addSerializer(WorkflowPlanTask.class, new WorkflowPlanTaskCheckpointSerializer());
        workflowStateModule.addSerializer(IntentRoutingResult.class,
                new IntentRoutingResultCheckpointSerializer());
        workflowStateModule.addDeserializer(IntentRoutingResult.class,
                new IntentRoutingResultCheckpointDeserializer());
        workflowStateModule.addSerializer(AgentTaskExecutionResult.class,
                new AgentTaskExecutionResultCheckpointSerializer());
        workflowStateModule.addDeserializer(AgentTaskExecutionResult.class,
                new AgentTaskExecutionResultCheckpointDeserializer());
        workflowStateModule.addSerializer(ProductCandidate.class, new ProductCandidateCheckpointSerializer());
        workflowStateModule.addSerializer(ConfirmedProduct.class, new ConfirmedProductCheckpointSerializer());
        workflowStateModule.addSerializer(ProductRecallResult.class, new ProductRecallResultCheckpointSerializer());
        workflowStateModule.addDeserializer(ProductRecallResult.class, new ProductRecallResultCheckpointDeserializer());
        workflowStateModule.addSerializer(DagExecutionResult.class, new DagExecutionResultCheckpointSerializer());
        workflowStateModule.addDeserializer(DagExecutionResult.class, new DagExecutionResultCheckpointDeserializer());
        workflowStateModule.addSerializer(WorkflowSummaryResult.class, new WorkflowSummaryResultCheckpointSerializer());
        workflowStateModule.addDeserializer(WorkflowSummaryResult.class, new WorkflowSummaryResultCheckpointDeserializer());
        workflowStateModule.addSerializer(OutputReviewResult.class, new OutputReviewResultCheckpointSerializer());
        workflowStateModule.addDeserializer(OutputReviewResult.class, new OutputReviewResultCheckpointDeserializer());
        checkpointObjectMapper.registerModule(workflowStateModule);
        return new SpringAIJacksonStateSerializer(OverAllState::new, checkpointObjectMapper);
    }

    private static final class IntentRoutingResultCheckpointSerializer
            extends StdSerializer<IntentRoutingResult> {

        /** 注册意图路由结果，避免 1.1.2.0 将嵌套 routes 元素退化为 Map 后无法再次持久化。 */
        private IntentRoutingResultCheckpointSerializer() {
            super(IntentRoutingResult.class);
        }

        /** 写入受控路由及显式类型标记，并兼容框架运行时产生的 Map 元素。 */
        @Override
        public void serialize(IntentRoutingResult value,
                              JsonGenerator generator,
                              SerializerProvider provider) throws IOException {
            generator.writeStartObject();
            generator.writeStringField("@typeHint", IntentRoutingResult.class.getName());
            writeNullableString(generator, "intent", value.intent());
            writeNullableString(generator, "targetAgent", value.targetAgent());
            writeNullableString(generator, "reason", value.reason());
            generator.writeArrayFieldStart("routes");
            for (Object routeValue : value.routes()) {
                IntentRoute route = normalizeRoute(routeValue);
                generator.writeStartObject();
                generator.writeStringField("@typeHint", IntentRoute.class.getName());
                writeNullableString(generator, "intent", route.intent());
                writeNullableString(generator, "targetAgent", route.targetAgent());
                writeNullableString(generator, "intentionQuery", route.intentionQuery());
                writeNullableString(generator, "reason", route.reason());
                generator.writeEndObject();
            }
            generator.writeEndArray();
            generator.writeEndObject();
        }

        /** 在框架类型序列化路径中复用相同载荷。 */
        @Override
        public void serializeWithType(IntentRoutingResult value,
                                      JsonGenerator generator,
                                      SerializerProvider provider,
                                      TypeSerializer typeSerializer) throws IOException {
            serialize(value, generator, provider);
        }

        private IntentRoute normalizeRoute(Object value) throws IOException {
            if (value instanceof IntentRoute route) {
                return route;
            }
            if (value instanceof Map<?, ?> route) {
                return new IntentRoute(
                        nullableMapText(route, "intent"),
                        nullableMapText(route, "targetAgent"),
                        nullableMapText(route, "intentionQuery"),
                        nullableMapText(route, "reason"));
            }
            throw new IOException("Unsupported intent route checkpoint value: "
                    + (value == null ? "null" : value.getClass().getName()));
        }

        private String nullableMapText(Map<?, ?> value, String key) {
            Object field = value.get(key);
            return field == null ? null : field.toString();
        }

        private void writeNullableString(JsonGenerator generator, String field, String value) throws IOException {
            if (value == null) {
                generator.writeNullField(field);
            }
            else {
                generator.writeStringField(field, value);
            }
        }
    }

    private static final class IntentRoutingResultCheckpointDeserializer
            extends StdDeserializer<IntentRoutingResult> {

        /** 注册意图路由结果反序列化类型。 */
        private IntentRoutingResultCheckpointDeserializer() {
            super(IntentRoutingResult.class);
        }

        /** 恢复外层结果和强类型 IntentRoute 列表。 */
        @Override
        public IntentRoutingResult deserialize(JsonParser parser,
                                               DeserializationContext context) throws IOException {
            JsonNode root = parser.getCodec().readTree(parser);
            List<IntentRoute> routes = new ArrayList<>();
            for (JsonNode route : root.path("routes")) {
                routes.add(new IntentRoute(
                        nullableText(route.path("intent")),
                        nullableText(route.path("targetAgent")),
                        nullableText(route.path("intentionQuery")),
                        nullableText(route.path("reason"))));
            }
            return new IntentRoutingResult(
                    nullableText(root.path("intent")),
                    nullableText(root.path("targetAgent")),
                    nullableText(root.path("reason")),
                    List.copyOf(routes));
        }

        private String nullableText(JsonNode node) {
            return node.isMissingNode() || node.isNull() ? null : node.asText();
        }
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
            generator.writeStringField("agentType", value.agentType());
            generator.writeStringField("query", value.query());
            generator.writeObjectField("dependsOn", value.dependsOn());
            generator.writeNumberField("maxRetries", value.maxRetries());
            generator.writeBooleanField("required", value.required());
            generator.writeEndObject();
        }
    }

    private static final class AgentTaskExecutionResultCheckpointSerializer
            extends StdSerializer<AgentTaskExecutionResult> {

        /** 注册单任务子图直接持久化的任务结果类型。 */
        private AgentTaskExecutionResultCheckpointSerializer() {
            super(AgentTaskExecutionResult.class);
        }

        /** 写入任务状态、响应、重试次数和可为空的中间态时间。 */
        @Override
        public void serialize(AgentTaskExecutionResult value,
                              JsonGenerator generator,
                              SerializerProvider provider) throws IOException {
            generator.writeStartObject();
            generator.writeStringField("@typeHint", AgentTaskExecutionResult.class.getName());
            generator.writeStringField("taskId", value.taskId());
            generator.writeNumberField("sequence", value.sequence());
            generator.writeStringField("agentName", value.agentName());
            generator.writeStringField("status", value.status().name());
            generator.writeObjectField("response", value.response());
            generator.writeStringField("errorCode", value.errorCode());
            generator.writeStringField("errorMessage", value.errorMessage());
            writeInstant(generator, "startedAt", value.startedAt());
            writeInstant(generator, "endedAt", value.endedAt());
            generator.writeNumberField("durationMs", value.durationMs());
            generator.writeNumberField("attempts", value.attempts());
            generator.writeEndObject();
        }

        /** 允许框架类型序列化路径复用相同 JSON 结构。 */
        @Override
        public void serializeWithType(AgentTaskExecutionResult value,
                                      JsonGenerator generator,
                                      SerializerProvider provider,
                                      TypeSerializer typeSerializer) throws IOException {
            serialize(value, generator, provider);
        }

        /** 写入可为空的执行时间。 */
        private void writeInstant(JsonGenerator generator, String field, Instant value) throws IOException {
            if (value == null) {
                generator.writeNullField(field);
            }
            else {
                generator.writeStringField(field, value.toString());
            }
        }
    }

    private static final class AgentTaskExecutionResultCheckpointDeserializer
            extends StdDeserializer<AgentTaskExecutionResult> {

        /** 注册单任务结果反序列化类型。 */
        private AgentTaskExecutionResultCheckpointDeserializer() {
            super(AgentTaskExecutionResult.class);
        }

        /** 恢复任务中间态或终态，并兼容旧 Checkpoint 缺少 attempts 字段。 */
        @Override
        public AgentTaskExecutionResult deserialize(JsonParser parser,
                                                    DeserializationContext context) throws IOException {
            JsonNode task = parser.getCodec().readTree(parser);
            ObjectMapper mapper = (ObjectMapper) parser.getCodec();
            JsonNode responseNode = task.path("response");
            SubAgentExecutionResult response = responseNode.isMissingNode() || responseNode.isNull()
                    ? null
                    : mapper.treeToValue(responseNode, SubAgentExecutionResult.class);
            AgentTaskStatus status = AgentTaskStatus.valueOf(task.path("status").asText());
            return new AgentTaskExecutionResult(
                    task.path("taskId").asText(),
                    task.path("sequence").asInt(),
                    task.path("agentName").asText(),
                    status,
                    response,
                    nullableText(task.path("errorCode")),
                    nullableText(task.path("errorMessage")),
                    nullableInstant(task.path("startedAt")),
                    nullableInstant(task.path("endedAt")),
                    task.path("durationMs").asLong(),
                    task.has("attempts") ? task.path("attempts").asInt()
                            : (status == AgentTaskStatus.SKIPPED_DEPENDENCY_FAILED ? 0 : 1));
        }

        /** 将 JSON null 转换为 Java null。 */
        private String nullableText(JsonNode node) {
            return node.isMissingNode() || node.isNull() ? null : node.asText();
        }

        /** 将可为空时间字段恢复为 Instant。 */
        private Instant nullableInstant(JsonNode node) {
            return node.isMissingNode() || node.isNull() ? null : Instant.parse(node.asText());
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
                new AgentTaskExecutionResultCheckpointSerializer().serialize(task, generator, provider);
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
                JsonParser taskParser = task.traverse(mapper);
                taskParser.nextToken();
                taskResults.add(new AgentTaskExecutionResultCheckpointDeserializer()
                        .deserialize(taskParser, context));
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

    private static final class WorkflowSummaryResultCheckpointSerializer extends StdSerializer<WorkflowSummaryResult> {

        /** 注册 WorkflowSummaryResult 的 Checkpoint 序列化目标类型。 */
        private WorkflowSummaryResultCheckpointSerializer() {
            super(WorkflowSummaryResult.class);
        }

        /** 写入 Summary 策略、任务统计、候选答案和调用元数据。 */
        @Override
        public void serialize(WorkflowSummaryResult value,
                              JsonGenerator generator,
                              SerializerProvider provider) throws IOException {
            generator.writeStartObject();
            generator.writeStringField("@typeHint", WorkflowSummaryResult.class.getName());
            generator.writeStringField("summaryId", value.summaryId());
            generator.writeBooleanField("modelInvoked", value.modelInvoked());
            generator.writeNumberField("sourceTaskCount", value.sourceTaskCount());
            generator.writeNumberField("successfulTaskCount", value.successfulTaskCount());
            generator.writeStringField("answer", value.answer());
            generator.writeNumberField("durationMs", value.durationMs());
            generator.writeStringField("summarizedAt", value.summarizedAt().toString());
            generator.writeEndObject();
        }

        /** 在框架请求带类型序列化时复用自定义类型标记格式。 */
        @Override
        public void serializeWithType(WorkflowSummaryResult value,
                                      JsonGenerator generator,
                                      SerializerProvider provider,
                                      TypeSerializer typeSerializer) throws IOException {
            serialize(value, generator, provider);
        }
    }

    private static final class WorkflowSummaryResultCheckpointDeserializer
            extends StdDeserializer<WorkflowSummaryResult> {

        /** 注册 WorkflowSummaryResult 的 Checkpoint 反序列化目标类型。 */
        private WorkflowSummaryResultCheckpointDeserializer() {
            super(WorkflowSummaryResult.class);
        }

        /** 将 Checkpoint JSON 恢复为具体 Summary 结果。 */
        @Override
        public WorkflowSummaryResult deserialize(JsonParser parser,
                                                 DeserializationContext context) throws IOException {
            JsonNode root = parser.getCodec().readTree(parser);
            return new WorkflowSummaryResult(
                    root.path("summaryId").asText(),
                    root.path("modelInvoked").asBoolean(),
                    root.path("sourceTaskCount").asInt(),
                    root.path("successfulTaskCount").asInt(),
                    root.path("answer").asText(),
                    root.path("durationMs").asLong(),
                    Instant.parse(root.path("summarizedAt").asText()));
        }
    }

    private static final class OutputReviewResultCheckpointSerializer extends StdSerializer<OutputReviewResult> {

        /** 注册 OutputReviewResult 的 Checkpoint 序列化目标类型。 */
        private OutputReviewResultCheckpointSerializer() {
            super(OutputReviewResult.class);
        }

        /** 写入审核决策、唯一可发布答案、原因和调用元数据。 */
        @Override
        public void serialize(OutputReviewResult value,
                              JsonGenerator generator,
                              SerializerProvider provider) throws IOException {
            generator.writeStartObject();
            generator.writeStringField("@typeHint", OutputReviewResult.class.getName());
            generator.writeStringField("reviewRequestId", value.reviewRequestId());
            generator.writeStringField("decision", value.decision().name());
            generator.writeStringField("publishableAnswer", value.publishableAnswer());
            generator.writeObjectField("reasons", value.reasons());
            generator.writeBooleanField("mockData", value.mockData());
            generator.writeNumberField("durationMs", value.durationMs());
            generator.writeStringField("reviewedAt", value.reviewedAt().toString());
            generator.writeEndObject();
        }

        /** 在框架请求带类型序列化时复用自定义类型标记格式。 */
        @Override
        public void serializeWithType(OutputReviewResult value,
                                      JsonGenerator generator,
                                      SerializerProvider provider,
                                      TypeSerializer typeSerializer) throws IOException {
            serialize(value, generator, provider);
        }
    }

    private static final class OutputReviewResultCheckpointDeserializer extends StdDeserializer<OutputReviewResult> {

        /** 注册 OutputReviewResult 的 Checkpoint 反序列化目标类型。 */
        private OutputReviewResultCheckpointDeserializer() {
            super(OutputReviewResult.class);
        }

        /** 将 Checkpoint JSON 恢复为具体输出审核结果。 */
        @Override
        public OutputReviewResult deserialize(JsonParser parser,
                                              DeserializationContext context) throws IOException {
            JsonNode root = parser.getCodec().readTree(parser);
            List<String> reasons = new ArrayList<>();
            root.path("reasons").forEach(reason -> reasons.add(reason.asText()));
            return new OutputReviewResult(
                    root.path("reviewRequestId").asText(),
                    OutputReviewDecision.valueOf(root.path("decision").asText()),
                    root.path("publishableAnswer").asText(),
                    reasons,
                    root.path("mockData").asBoolean(),
                    root.path("durationMs").asLong(),
                    Instant.parse(root.path("reviewedAt").asText()));
        }
    }
}
