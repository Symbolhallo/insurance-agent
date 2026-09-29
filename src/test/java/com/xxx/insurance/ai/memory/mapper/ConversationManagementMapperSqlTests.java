package com.xxx.insurance.ai.memory.mapper;

import com.xxx.insurance.common.security.RequestIdentity;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationManagementMapperSqlTests {

    @Test
    void listExcludesDeletedConversationsAndUsesLongTermMessageCount() throws Exception {
        String sql = selectSql("findActiveConversations", RequestIdentity.class, int.class);

        assertThat(sql)
                .contains("c.status <> 'DELETED'")
                .contains("c.tenant_id = #{identity.tenantId}")
                .contains("c.user_id = #{identity.userId}")
                .contains("from ai_long_term_memory")
                .contains("memory_type = 'MESSAGE'")
                .contains("order by c.updated_at desc")
                .contains("limit #{limit}");
    }

    @Test
    void archiveRejectsActiveWorkflowAndUnexpiredConversationLease() throws Exception {
        String sql = updateSql(
                "archiveConversation", RequestIdentity.class, String.class, Instant.class);

        assertThat(sql)
                .contains("set status = 'DELETED'")
                .contains("tenant_id = #{identity.tenantId}")
                .contains("user_id = #{identity.userId}")
                .contains("i.status in ('RUNNING', 'CONFIRMING', 'RESUMING', 'WAITING_CONFIRM')")
                .contains("from ai_conversation_workflow_lock l")
                .contains("l.lease_until > #{deletedAt}");
        assertThat(sql).doesNotContain("delete from");
    }

    private String selectSql(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = ConversationManagementMapper.class.getMethod(methodName, parameterTypes);
        return String.join("\n", method.getAnnotation(Select.class).value());
    }

    private String updateSql(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = ConversationManagementMapper.class.getMethod(methodName, parameterTypes);
        return String.join("\n", method.getAnnotation(Update.class).value());
    }
}
