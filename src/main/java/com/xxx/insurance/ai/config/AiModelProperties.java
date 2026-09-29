package com.xxx.insurance.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 模型运行配置。
 *
 * <p>该配置由 AgentScope OpenAI-compatible Model 直接消费，并供本地联调状态检查和
 * 后续 Model Router 演进复用。API Key 只判断是否配置，不允许通过任何接口明文返回。</p>
 */
@ConfigurationProperties(prefix = "insurance.ai.model")
public class AiModelProperties {

    private String apiKey = "";

    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode";

    private String modelName = "qwen-plus";

    private Double temperature = 0.2;

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

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }
}
