package com.xxx.insurance.ai.config;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitExceededException;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentSafetyConfigTests {

    @Test
    void limitsEachRunnableConfigIndependently() {
        AgentSafetyProperties properties = new AgentSafetyProperties();
        ModelCallLimitHook hook = new AgentSafetyConfig().domainAgentModelCallLimitHook(properties);
        RunnableConfig firstRun = RunnableConfig.builder().build();
        OverAllState state = new OverAllState();

        for (int index = 0; index < properties.getModelCallLimit(); index++) {
            hook.beforeModel(state, firstRun).join();
            hook.afterModel(state, firstRun).join();
        }

        assertThatThrownBy(() -> hook.beforeModel(state, firstRun))
                .isInstanceOf(ModelCallLimitExceededException.class);
        assertThatCode(() -> hook.beforeModel(state, RunnableConfig.builder().build()).join())
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnsafeLimitConfiguration() {
        AgentSafetyProperties properties = new AgentSafetyProperties();
        properties.setModelCallLimit(1);

        assertThatThrownBy(() -> new AgentSafetyConfig().domainAgentModelCallLimitHook(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelCallLimit");
    }
}
