package com.xxx.insurance.common.security;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;

/** OceanBase 根资源所有权 SQL；子表只能在根资源授权后按 conversation/workflow 主键访问。 */
@Mapper
public interface ResourceAccessMapper {

    /** 首次请求创建会话所有权占位；主键冲突时不覆盖原租户和用户。 */
    @Insert("""
            insert into ai_conversation (
                conversation_id, tenant_id, user_id, customer_id, operator_id,
                session_type, agent_name, title, status, created_at, updated_at
            ) values (
                #{conversationId}, #{identity.tenantId}, #{identity.userId},
                #{identity.customerId}, #{identity.operatorId},
                'INTERNAL_TEST', 'identity-reservation', null, 'ACTIVE', #{now}, #{now}
            )
            on duplicate key update conversation_id = values(conversation_id)
            """)
    int claimConversation(@Param("identity") RequestIdentity identity,
                          @Param("conversationId") String conversationId,
                          @Param("now") Instant now);

    /** 精确校验会话租户和用户；已归档会话不再允许继续执行新业务。 */
    @Select("""
            select count(*)
            from ai_conversation
            where conversation_id = #{conversationId}
              and tenant_id = #{identity.tenantId}
              and user_id = #{identity.userId}
              and status <> 'DELETED'
            """)
    int countOwnedConversation(@Param("identity") RequestIdentity identity,
                               @Param("conversationId") String conversationId);

    /** 精确校验工作流实例归属，不通过 conversationId 间接猜测所有者。 */
    @Select("""
            select count(*)
            from ai_workflow_instance
            where workflow_instance_id = #{workflowInstanceId}
              and tenant_id = #{identity.tenantId}
              and user_id = #{identity.userId}
            """)
    int countOwnedWorkflow(@Param("identity") RequestIdentity identity,
                           @Param("workflowInstanceId") String workflowInstanceId);
}
