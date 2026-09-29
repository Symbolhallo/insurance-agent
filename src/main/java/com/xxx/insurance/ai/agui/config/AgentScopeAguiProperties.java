package com.xxx.insurance.ai.agui.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** AgentScope AG-UI HTTP 流配置。 */
@ConfigurationProperties(prefix = "insurance.ai.agui")
public class AgentScopeAguiProperties {

    /** 单次直连领域 Agent 的 AG-UI SSE 最长运行时间。 */
    private Duration runTimeout = Duration.ofMinutes(10);

    public Duration getRunTimeout() {
        return runTimeout;
    }

    public void setRunTimeout(Duration runTimeout) {
        this.runTimeout = runTimeout;
    }
}
