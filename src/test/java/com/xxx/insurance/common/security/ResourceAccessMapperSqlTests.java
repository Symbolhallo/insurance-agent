package com.xxx.insurance.common.security;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceAccessMapperSqlTests {

    @Test
    void conversationClaimCannotOverwriteExistingTenantOrUser() throws Exception {
        String sql = insertSql(
                "claimConversation", RequestIdentity.class, String.class, Instant.class);

        assertThat(sql)
                .contains("tenant_id", "user_id")
                .contains("on duplicate key update conversation_id = values(conversation_id)")
                .doesNotContain("tenant_id = values", "user_id = values");
    }

    @Test
    void rootResourceChecksRequireBothTenantAndUser() throws Exception {
        String conversationSql = selectSql(
                "countOwnedConversation", RequestIdentity.class, String.class);
        String workflowSql = selectSql(
                "countOwnedWorkflow", RequestIdentity.class, String.class);

        assertTenantAndUserPredicates(conversationSql);
        assertTenantAndUserPredicates(workflowSql);
    }

    private void assertTenantAndUserPredicates(String sql) {
        assertThat(sql)
                .contains("tenant_id = #{identity.tenantId}")
                .contains("user_id = #{identity.userId}");
    }

    private String insertSql(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = ResourceAccessMapper.class.getMethod(methodName, parameterTypes);
        return String.join("\n", method.getAnnotation(Insert.class).value());
    }

    private String selectSql(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = ResourceAccessMapper.class.getMethod(methodName, parameterTypes);
        return String.join("\n", method.getAnnotation(Select.class).value());
    }
}
