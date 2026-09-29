package com.xxx.insurance.common.security;

import com.xxx.insurance.ai.memory.mapper.AgentConversationMapper;
import com.xxx.insurance.ai.memory.mapper.AgentInvocationMapper;
import com.xxx.insurance.ai.memory.mapper.LongTermMemoryMapper;
import com.xxx.insurance.ai.memory.model.AgentConversationRecord;
import com.xxx.insurance.ai.workflow.mapper.WorkflowExecutionMapper;
import com.xxx.insurance.ai.workflow.model.WorkflowInstanceRecord;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TenantOwnershipPersistenceSqlTests {

    @Test
    void workflowAndMemoryWritesPersistTenantIdentity() throws Exception {
        assertThat(insertSql(WorkflowExecutionMapper.class, "insertInstance", WorkflowInstanceRecord.class))
                .contains("tenant_id", "#{tenantId}", "#{userId}", "#{customerId}", "#{operatorId}");
        assertThat(insertSql(AgentInvocationMapper.class, "insert", AgentInvocationMapper.AgentInvocationWriteRecord.class))
                .contains("tenant_id", "#{tenantId}");
        assertThat(insertSql(LongTermMemoryMapper.class, "insert", LongTermMemoryMapper.LongTermMemoryWriteRecord.class))
                .contains("tenant_id", "#{tenantId}");
    }

    @Test
    void conversationUpsertPreservesTheOwnerEstablishedByTheClaim() throws Exception {
        String sql = insertSql(
                AgentConversationMapper.class, "upsertActiveConversation", AgentConversationRecord.class);

        assertThat(sql)
                .contains("tenant_id", "user_id")
                .doesNotContain("tenant_id = values", "user_id = values",
                        "customer_id = values", "operator_id = values");
    }

    @Test
    void migrationAddsTenantOwnershipColumnsAndIndexes() throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V21__add_tenant_resource_ownership.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("alter table ai_conversation")
                .contains("alter table ai_workflow_instance")
                .contains("alter table ai_agent_invocation")
                .contains("alter table ai_long_term_memory")
                .contains("idx_ai_conversation_tenant_user_status")
                .contains("idx_ai_workflow_instance_tenant_user_created");
    }

    private String insertSql(Class<?> mapperType,
                             String methodName,
                             Class<?>... parameterTypes) throws Exception {
        return String.join("\n", mapperType.getMethod(methodName, parameterTypes)
                .getAnnotation(Insert.class)
                .value());
    }
}
