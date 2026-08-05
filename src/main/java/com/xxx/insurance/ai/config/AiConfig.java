package com.xxx.insurance.ai.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionProperties;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI基础配置。
 *
 * <p>当前阶段采用单模型模式：应用启动时只创建一个全局复用的{@link ChatModel} Bean。
 * ChatModel 是 Spring AI 对“对话模型”的统一抽象，上层业务不直接依赖具体厂商 SDK，
 * 后续 ProductAnalysisAgent 只需要依赖 ChatModel 即可完成模型调用编排。</p>
 *
 * <p>Spring AI 的典型调用链为：Agent/Service 构造 Prompt -> ChatModel.call(...) ->
 * 底层模型适配器转换为供应商 HTTP 请求 -> 模型返回 ChatResponse -> Agent/Service 解析结果。
 * 本类只负责模型 Bean 装配，不引入 Agent、Tool Calling、Workflow 或 Memory 逻辑，
 * 保持 Phase1 工程初始化边界清晰。</p>
 *
 * <p>未来升级为 Agent -> Model Router -> 多模型选择时，可以保留 ChatModel 作为默认模型，
 * 并在 Router 层按业务域、成本、延迟、合规策略选择不同模型实例。ReactAgent 接入时，
 * 会在 Agent 配置中注入该 ChatModel，作为推理、计划和自然语言生成的基础能力。</p>
 */
@Configuration
public class AiConfig {

    /**
     * 创建全局对话模型 Bean。
     *
     * <p>这里使用 Spring AI OpenAI-compatible ChatModel 作为统一入口，便于接入支持
     * OpenAI 协议的模型服务；同时项目已引入 Spring AI Alibaba BOM、DashScope能力包
     * 和 Agent Framework。后续切换 DashScope 原生模型或接入 Spring AI Alibaba Agent
     * Framework 时，可以在本配置类内替换底层模型实现，而不影响 Agent 层依赖的
     * ChatModel 抽象。</p>
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
