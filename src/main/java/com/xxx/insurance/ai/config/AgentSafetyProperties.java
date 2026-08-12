package com.xxx.insurance.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Spring AI Alibaba ReactAgent 通用运行安全配置。 */
@ConfigurationProperties(prefix = "insurance.ai.agent.safety")
public class AgentSafetyProperties {

    /** 单次 Agent 运行允许的最大模型调用次数，覆盖正常 ReAct Tool 循环并阻止失控循环。 */
    private int modelCallLimit = 8;

    public int getModelCallLimit() {
        return modelCallLimit;
    }

    public void setModelCallLimit(int modelCallLimit) {
        this.modelCallLimit = modelCallLimit;
    }

    /** 校验运行上限，避免零值导致所有 Agent 调用立即失败。 */
    public void validate() {
        if (modelCallLimit < 2 || modelCallLimit > 50) {
            throw new IllegalArgumentException("modelCallLimit must be between 2 and 50");
        }
    }
}
