package com.xxx.insurance.ai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册 AgentScope ReAct 迭代上限配置，由 AgentScopeAgentFactory 应用于每个领域 Agent。 */
@Configuration
@EnableConfigurationProperties(AgentSafetyProperties.class)
public class AgentSafetyConfig {
}
