package com.xxx.insurance.ai.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionProperties;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI基础配置。
 *
 * <p>当前采用单模型模式：应用启动时只创建一个全局复用的 {@link ChatModel} Bean。
 * ChatModel 是 Spring AI 对“对话模型”的统一抽象，上层业务不直接依赖具体厂商 SDK，
 * 产品、知识、保单、资产、Planner、Summary 和前置结构化节点复用该模型能力。</p>
 *
 * <p>Spring AI 的典型调用链为：Agent/Service 构造 Prompt -> ChatModel.call(...) ->
 * 底层模型适配器转换为供应商 HTTP 请求 -> 模型返回 ChatResponse -> Agent/Service 解析结果。
 * 本类只负责模型连接与 Bean 装配；Agent、Tool、Workflow、Memory 分别在领域和编排配置中组合，
 * 因而切换兼容模型服务时不会把供应商配置扩散到业务代码。</p>
 *
 * <p>未来升级为 Agent -> Model Router -> 多模型选择时，可以保留 ChatModel 作为默认模型，
 * 并在 Router 层按业务域、成本、延迟、合规策略选择不同模型实例。ReactAgent 接入时，
 * 会在 Agent 配置中注入该 ChatModel，作为推理、计划和自然语言生成的基础能力。</p>
 */
@Configuration
@EnableConfigurationProperties(AiModelProperties.class)
public class AiConfig {

    /**
     * 创建全局对话模型 Bean。
     *
     * <p>使用 Spring AI OpenAI-compatible ChatModel 统一接入 DeepSeek、DashScope 兼容模式等服务；
     * API Key、Base URL 和默认模型选项由 Spring 配置绑定提供。Spring AI Alibaba ReactAgent 与 Graph
     * 节点只依赖 ChatModel 抽象，未来切换原生模型适配或引入 Model Router 时无需修改领域 Agent 合同。</p>
     *
     * @param connectionProperties Spring AI OpenAI 连接配置，读取 spring.ai.openai.* 配置项
     * @param chatProperties Spring AI OpenAI Chat 配置，读取 spring.ai.openai.chat.* 配置项
     * @return 应用全局复用的 ChatModel
     */
    @Bean
    public ChatModel chatModel(OpenAiConnectionProperties connectionProperties,
                               OpenAiChatProperties chatProperties) {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(connectionProperties.getBaseUrl())
                .apiKey(connectionProperties.getApiKey())
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(chatProperties.getOptions())
                .build();
    }
}
