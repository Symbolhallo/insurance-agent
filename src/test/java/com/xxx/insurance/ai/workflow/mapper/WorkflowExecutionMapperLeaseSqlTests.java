package com.xxx.insurance.ai.workflow.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowExecutionMapperLeaseSqlTests {

    @Test
    void expiredConversationLockCleanupProtectsLiveOwnerAndRecoverableCheckpoint() throws Exception {
        String targetedSql = deleteSql("deleteExpiredInvalidConversationLock", String.class, Instant.class);
        String bulkSql = deleteSql("deleteExpiredInvalidConversationLocks", Instant.class);

        assertSafeExpiredLockCleanup(targetedSql);
        assertSafeExpiredLockCleanup(bulkSql);
        assertThat(targetedSql).contains("l.conversation_id = #{conversationId}");
    }

    @Test
    void heartbeatCannotRenewExpiredLeaseOrLeaseOwnedByAnotherInstance() throws Exception {
        String sql = updateSql(
                "renewOwnedExecutionLeases", String.class, Instant.class, Instant.class);

        assertThat(sql)
                .contains("i.execution_owner = #{executionOwner}")
                .contains("i.status in ('RUNNING', 'CONFIRMING', 'RESUMING')")
                .contains("i.lease_until > #{now}")
                .contains("l.workflow_instance_id = i.workflow_instance_id")
                .contains("l.lease_until = #{leaseUntil}");
        assertThat(sql).doesNotContain("execution_fence_token = execution_fence_token + 1");
    }

    @Test
    void finalizationRequiresOwnerFenceTokenAndUnexpiredLease() throws Exception {
        String sql = updateSql(
                "finalizeInstance", String.class, String.class, String.class, String.class,
                String.class, long.class, Instant.class);

        assertThat(sql)
                .contains("execution_owner = #{executionOwner}")
                .contains("execution_fence_token = #{executionFenceToken}")
                .contains("lease_until > #{endedAt}");
    }

    @Test
    void claimsAdvanceFenceTokenButHeartbeatDoesNot() throws Exception {
        String confirmSql = updateSql(
                "claimProductConfirmation",
                String.class, String.class, String.class, Instant.class, Instant.class);
        String resumeSql = updateSql(
                "claimResume", String.class, String.class, String.class, Instant.class, Instant.class);

        assertThat(confirmSql).contains("execution_fence_token = execution_fence_token + 1");
        assertThat(resumeSql).contains("execution_fence_token = execution_fence_token + 1");
    }

    @Test
    void resumeClaimRequiresExpiredExecutionLeaseAndExistingConversationLock() throws Exception {
        String sql = updateSql(
                "claimResume", String.class, String.class, String.class, Instant.class, Instant.class);

        assertThat(sql)
                .contains("lease_until <= #{updatedAt}")
                .contains("from ai_conversation_workflow_lock l")
                .contains("l.workflow_instance_id = ai_workflow_instance.workflow_instance_id");
    }

    @Test
    void confirmationClaimRejectsExpiredConversationLock() throws Exception {
        String sql = updateSql(
                "claimProductConfirmation",
                String.class, String.class, String.class, Instant.class, Instant.class);

        assertThat(sql).contains("l.lease_until > #{updatedAt}");
    }

    private void assertSafeExpiredLockCleanup(String sql) {
        assertThat(sql)
                .contains("l.lease_until <= #{now}")
                .contains("i.lease_until <= #{now}")
                .contains("from ai_graph_thread t")
                .contains("t.expires_at > #{now}");
    }

    private String updateSql(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = WorkflowExecutionMapper.class.getMethod(methodName, parameterTypes);
        return String.join("\n", method.getAnnotation(Update.class).value());
    }

    private String deleteSql(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = WorkflowExecutionMapper.class.getMethod(methodName, parameterTypes);
        return String.join("\n", method.getAnnotation(Delete.class).value());
    }
}
