package com.xxx.insurance.ai.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentSafetyConfigTests {

    @Test
    void acceptsProductionDefaultLimit() {
        assertThatCode(new AgentSafetyProperties()::validate).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnsafeLimitConfiguration() {
        AgentSafetyProperties properties = new AgentSafetyProperties();
        properties.setMaxIterations(1);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxIterations");
    }
}
