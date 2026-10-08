package com.xxx.insurance.ai.workflow.checkpoint.config;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.serializer.StateSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.workflow.checkpoint.GraphCheckpointStateCodec;
import com.xxx.insurance.ai.workflow.checkpoint.OceanBaseCheckpointSaver;
import com.xxx.insurance.ai.workflow.checkpoint.mapper.GraphCheckpointMapper;
import com.xxx.insurance.ai.workflow.checkpoint.serialization.MainWorkflowCheckpointSerde;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 装配 Main Graph 状态序列化器、二进制 Codec 和 OceanBase CheckpointSaver。 */
@Configuration
@EnableConfigurationProperties(GraphCheckpointProperties.class)
public class GraphCheckpointConfig {

    public static final String MAIN_WORKFLOW_STATE_SERIALIZER = "mainWorkflowStateSerializer";
    public static final String MAIN_WORKFLOW_CHECKPOINT_SAVER = "mainWorkflowCheckpointSaver";

    /**
     * 创建 Main Graph 专属状态序列化器；兼容 DTO 规则由独立 Serde 类维护。
     *
     * @param objectMapper Spring Boot 全局 ObjectMapper，仅作为复制和基础配置来源
     * @return StateGraph 和 OceanBase Saver 共同使用的 StateSerializer
     */
    @Bean(MAIN_WORKFLOW_STATE_SERIALIZER)
    public StateSerializer mainWorkflowStateSerializer(ObjectMapper objectMapper) {
        return MainWorkflowCheckpointSerde.createStateSerializer(objectMapper);
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
}
