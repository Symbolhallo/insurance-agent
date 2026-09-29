package com.xxx.insurance.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** AgentScope HarnessAgent 通用运行安全配置。 */
@ConfigurationProperties(prefix = "insurance.ai.agent.safety")
public class AgentSafetyProperties {

    /** 单次 Agent 运行允许的最大 ReAct 轮数，覆盖正常 Tool 循环并阻止失控循环。 */
    private int maxIterations = 8;

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    /** 校验运行上限，避免零值导致所有 Agent 调用立即失败。 */
    public void validate() {
        if (maxIterations < 2 || maxIterations > 50) {
            throw new IllegalArgumentException("maxIterations must be between 2 and 50");
        }
    }
}
