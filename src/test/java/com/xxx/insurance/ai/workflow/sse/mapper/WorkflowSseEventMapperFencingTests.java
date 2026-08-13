package com.xxx.insurance.ai.workflow.sse.mapper;

import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowSseEventMapperFencingTests {

    @Test
    void executionEventRequiresOwnerFenceTokenAndLiveLease() throws Exception {
        String sql = updateSql(
                "allocateExecutionSequence", String.class, String.class, long.class, Instant.class);

        assertThat(sql)
                .contains("execution_owner = #{executionOwner}")
                .contains("execution_fence_token = #{executionFenceToken}")
                .contains("lease_until > #{now}")
                .contains("status in ('RUNNING', 'CONFIRMING', 'RESUMING')");
    }

    @Test
    void terminalAndWaitingEventsRemainBoundToTheirExecutionEpoch() throws Exception {
        assertThat(updateSql("allocateTerminalSequence", String.class, long.class))
                .contains("execution_fence_token = #{executionFenceToken}")
                .contains("status in ('SUCCESS', 'PARTIAL_SUCCESS', 'REVIEW_BLOCKED', 'FAILED')");
        assertThat(updateSql("allocateWaitingConfirmSequence", String.class, long.class))
                .contains("execution_fence_token = #{executionFenceToken}")
                .contains("status = 'WAITING_CONFIRM'");
    }

    private String updateSql(String name, Class<?>... types) throws Exception {
        return String.join("\n", WorkflowSseEventMapper.class.getMethod(name, types)
                .getAnnotation(Update.class).value());
    }
}
