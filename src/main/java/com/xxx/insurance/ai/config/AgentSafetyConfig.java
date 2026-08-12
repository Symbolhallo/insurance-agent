package com.xxx.insurance.ai.config;

import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 配置 Spring AI Alibaba 原生 Agent 调用保护能力。 */
@Configuration
@EnableConfigurationProperties(AgentSafetyProperties.class)
public class AgentSafetyConfig {

    public static final String DOMAIN_AGENT_MODEL_CALL_LIMIT_HOOK = "domainAgentModelCallLimitHook";

    /**
     * 创建四个领域 ReactAgent 复用的模型调用上限 Hook。
     *
     * <p>使用 ERROR 行为让超限进入现有失败审计，避免把框架生成的英文限流文本当成金融回答。</p>
     */
    @Bean(DOMAIN_AGENT_MODEL_CALL_LIMIT_HOOK)
    public ModelCallLimitHook domainAgentModelCallLimitHook(AgentSafetyProperties properties) {
        properties.validate();
        return ModelCallLimitHook.builder()
                .runLimit(properties.getModelCallLimit())
                .exitBehavior(ModelCallLimitHook.ExitBehavior.ERROR)
                .build();
    }
}
