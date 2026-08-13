package com.xxx.insurance.ai.workflow.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class WorkflowSsePropertiesTests {

    @Test
    void usesLowLatencyTokenBatchDefaults() {
        WorkflowSseProperties properties = new WorkflowSseProperties(null, null, null, null, 0);

        assertThat(properties.tokenBatchMaxDelay()).isEqualTo(Duration.ofMillis(80));
        assertThat(properties.tokenBatchMaxCharacters()).isEqualTo(128);
    }

    @Test
    void rejectsTokenBatchDelayThatWouldBreakInteractiveStreaming() {
        assertThatIllegalArgumentException().isThrownBy(() -> new WorkflowSseProperties(
                null, null, null, Duration.ofSeconds(2), 128));
        assertThatIllegalArgumentException().isThrownBy(() -> new WorkflowSseProperties(
                null, null, null, Duration.ofNanos(1), 128));
    }
}
