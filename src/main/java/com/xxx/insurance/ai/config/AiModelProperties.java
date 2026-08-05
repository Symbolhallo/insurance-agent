package com.xxx.insurance.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 模型运行配置。
 *
 * <p>该配置类只映射 Spring AI OpenAI-compatible 配置项，用于本地联调状态检查和
 * 后续 Model Router 演进时复用。API Key 只判断是否配置，不允许通过任何接口明文返回。</p>
 */
@ConfigurationProperties(prefix = "spring.ai.openai")
public class AiModelProperties {

    private String apiKey;

    private String baseUrl;

    private Chat chat = new Chat();

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Chat getChat() {
        return chat;
    }

    public void setChat(Chat chat) {
        this.chat = chat;
    }

    public static class Chat {

        private Options options = new Options();

        public Options getOptions() {
            return options;
        }

        public void setOptions(Options options) {
            this.options = options;
        }
    }

    public static class Options {

        private String model;

        private Double temperature;

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }
    }
}
