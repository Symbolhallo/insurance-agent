package com.xxx.insurance.ai.config;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI基础配置。
 *
 * <p>当前保持单模型策略，全项目模型调用统一依赖 AgentScope {@link Model}。领域 HarnessAgent、
 * Planner、Summary 以及上下文对齐、意图识别等确定性前置节点复用同一个线程安全模型实例。</p>
 *
 * <p>调用链统一为 HarnessAgent/Service -> AgentScope Model -> OpenAI-compatible HTTP。本类只负责连接与 Bean 装配，
 * Agent、Tool、Workflow、Memory 分别在领域和编排配置中组合。</p>
 *
 * <p>前置结构化节点已经统一通过 AgentScope Model 调用。后续若建设 Model Router，可在不改变
 * Agent 和 Workflow 业务接口的前提下，按业务域、成本、延迟和合规策略选择模型实例。</p>
 */
@Configuration
@EnableConfigurationProperties(AiModelProperties.class)
public class AiConfig {

    /**
     * 创建 AgentScope 全局模型。
     *
     * <p>AgentScope HarnessAgent、Toolkit 和 SkillRepository 统一复用该线程安全 Model；DeepSeek
     * 通过 OpenAI-compatible HTTP 协议接入，并使用官方 DeepSeekFormatter 处理 Tool Calling。
     * API Key、Base URL、模型名和温度继续读取既有外部化配置，业务代码不接触密钥。</p>
     */
    @Bean
    public Model agentScopeModel(AiModelProperties properties) {
        GenerateOptions options = GenerateOptions.builder()
                .modelName(properties.getModelName())
                .temperature(properties.getTemperature())
                .stream(true)
                .build();
        return OpenAIChatModel.builder()
                .apiKey(properties.getApiKey())
                .baseUrl(properties.getBaseUrl())
                .modelName(properties.getModelName())
                .formatter(new DeepSeekFormatter())
                .generateOptions(options)
                .stream(true)
                .build();
    }

}
