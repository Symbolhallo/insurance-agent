package com.xxx.insurance.ai.workflow.mapper;

import com.xxx.insurance.ai.workflow.model.WorkflowInstanceRecord;
import com.xxx.insurance.ai.workflow.model.WorkflowStepRecord;
import com.xxx.insurance.ai.workflow.model.WorkflowInstanceExecutionView;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * Workflow 执行实例与步骤 Mapper。
 */
@Mapper
public interface WorkflowExecutionMapper {

    @Select("""
            select workflow_instance_id,
                   conversation_id,
                   status,
                   execution_owner,
                   lease_until,
                   execution_fence_token,
                   created_at
            from ai_workflow_instance
            where workflow_instance_id = #{workflowInstanceId}
            """)
    @ConstructorArgs({
            @Arg(column = "workflow_instance_id", javaType = String.class),
            @Arg(column = "conversation_id", javaType = String.class),
            @Arg(column = "status", javaType = String.class),
            @Arg(column = "execution_owner", javaType = String.class),
            @Arg(column = "lease_until", javaType = java.time.Instant.class),
            @Arg(column = "execution_fence_token", javaType = long.class),
            @Arg(column = "created_at", javaType = java.time.Instant.class)
    })
    WorkflowInstanceExecutionView findInstance(@Param("workflowInstanceId") String workflowInstanceId);

    @Insert("""
            insert into ai_workflow_instance (
                workflow_instance_id,
                workflow_code,
                conversation_id,
                request_id,
                trace_id,
                status,
                input_json,
                execution_owner,
                lease_until,
                execution_fence_token,
                state_version,
                created_at,
                updated_at
            ) values (
                #{workflowInstanceId},
                #{workflowCode},
                #{conversationId},
                #{requestId},
                #{traceId},
                #{status},
                #{inputJson},
                #{executionOwner},
                #{leaseUntil},
                1,
                1,
                #{createdAt},
                #{createdAt}
            )
            """)
    void insertInstance(WorkflowInstanceRecord record);

    /**
     * 原子写入正常业务终态。终态保护条件保证重复收口或迟到异常不能覆盖既有结果。
     */
    @Update("""
            update ai_workflow_instance
            set status = #{status},
                output_json = #{outputJson},
                error_message = #{errorMessage},
                execution_owner = null,
                lease_until = null,
                state_version = state_version + 1,
                updated_at = #{endedAt}
            where workflow_instance_id = #{workflowInstanceId}
              and execution_owner = #{executionOwner}
              and execution_fence_token = #{executionFenceToken}
              and lease_until > #{endedAt}
              and status not in ('SUCCESS', 'PARTIAL_SUCCESS', 'REVIEW_BLOCKED', 'FAILED')
            """)
    int finalizeInstance(@Param("workflowInstanceId") String workflowInstanceId,
                         @Param("status") String status,
                         @Param("outputJson") String outputJson,
                         @Param("errorMessage") String errorMessage,
                         @Param("executionOwner") String executionOwner,
                         @Param("executionFenceToken") long executionFenceToken,
                         @Param("endedAt") java.time.Instant endedAt);

    /** 仅允许非终态实例迁移为 FAILED，避免外层迟到 catch 覆盖已提交业务终态。 */
    @Update("""
            update ai_workflow_instance
            set status = 'FAILED',
                output_json = null,
                error_message = #{errorMessage},
                execution_owner = null,
                lease_until = null,
                state_version = state_version + 1,
                updated_at = #{endedAt}
            where workflow_instance_id = #{workflowInstanceId}
              and execution_owner = #{executionOwner}
              and execution_fence_token = #{executionFenceToken}
              and lease_until > #{endedAt}
              and status not in ('SUCCESS', 'PARTIAL_SUCCESS', 'REVIEW_BLOCKED', 'FAILED')
            """)
    int failInstanceIfNonTerminal(@Param("workflowInstanceId") String workflowInstanceId,
                                  @Param("errorMessage") String errorMessage,
                                  @Param("executionOwner") String executionOwner,
                                  @Param("executionFenceToken") long executionFenceToken,
                                  @Param("endedAt") java.time.Instant endedAt);

    @Update("""
            update ai_workflow_instance
            set status = #{status},
                output_json = #{outputJson},
                error_message = null,
                execution_owner = null,
                lease_until = null,
                state_version = state_version + 1,
                updated_at = #{updatedAt}
            where workflow_instance_id = #{workflowInstanceId}
              and execution_owner = #{executionOwner}
              and execution_fence_token = #{executionFenceToken}
              and lease_until > #{updatedAt}
            """)
    int updateInstanceStatus(@Param("workflowInstanceId") String workflowInstanceId,
                             @Param("status") String status,
                             @Param("outputJson") String outputJson,
                             @Param("executionOwner") String executionOwner,
                             @Param("executionFenceToken") long executionFenceToken,
                             @Param("updatedAt") java.time.Instant updatedAt);

    /** 原子抢占等待产品确认的实例，防止多个确认请求从同一 Checkpoint 重复恢复。 */
    @Update("""
            update ai_workflow_instance
            set status = 'CONFIRMING',
                error_message = null,
                execution_owner = #{executionOwner},
                lease_until = #{leaseUntil},
                execution_fence_token = execution_fence_token + 1,
                state_version = state_version + 1,
                updated_at = #{updatedAt}
            where workflow_instance_id = #{workflowInstanceId}
              and conversation_id = #{conversationId}
              and status = 'WAITING_CONFIRM'
              and exists (
                  select 1
                  from ai_conversation_workflow_lock l
                  where l.workflow_instance_id = ai_workflow_instance.workflow_instance_id
                    and l.conversation_id = ai_workflow_instance.conversation_id
                    and l.lease_until > #{updatedAt}
              )
            """)
    int claimProductConfirmation(@Param("workflowInstanceId") String workflowInstanceId,
                                 @Param("conversationId") String conversationId,
                                 @Param("executionOwner") String executionOwner,
                                 @Param("leaseUntil") java.time.Instant leaseUntil,
                                 @Param("updatedAt") java.time.Instant updatedAt);

    /** 后台任务尚未提交时释放确认抢占，允许用户重新提交。 */
    @Update("""
            update ai_workflow_instance
            set status = 'WAITING_CONFIRM',
                execution_owner = null,
                lease_until = null,
                state_version = state_version + 1,
                updated_at = #{updatedAt}
            where workflow_instance_id = #{workflowInstanceId}
              and conversation_id = #{conversationId}
              and status = 'CONFIRMING'
              and execution_owner = #{executionOwner}
              and execution_fence_token = #{executionFenceToken}
            """)
    int releaseProductConfirmationClaim(@Param("workflowInstanceId") String workflowInstanceId,
                                        @Param("conversationId") String conversationId,
                                        @Param("executionOwner") String executionOwner,
                                        @Param("executionFenceToken") long executionFenceToken,
                                        @Param("updatedAt") java.time.Instant updatedAt);

    @Update("""
            update ai_workflow_instance
            set status = 'RESUMING',
                error_message = null,
                execution_owner = #{executionOwner},
                lease_until = #{leaseUntil},
                execution_fence_token = execution_fence_token + 1,
                state_version = state_version + 1,
                updated_at = #{updatedAt}
            where workflow_instance_id = #{workflowInstanceId}
              and conversation_id = #{conversationId}
              and status = 'RUNNING'
              and (execution_owner is null or lease_until is null or lease_until <= #{updatedAt})
              and exists (
                  select 1
                  from ai_conversation_workflow_lock l
                  where l.workflow_instance_id = ai_workflow_instance.workflow_instance_id
                    and l.conversation_id = ai_workflow_instance.conversation_id
              )
            """)
    int claimResume(@Param("workflowInstanceId") String workflowInstanceId,
                    @Param("conversationId") String conversationId,
                    @Param("executionOwner") String executionOwner,
                    @Param("leaseUntil") java.time.Instant leaseUntil,
                    @Param("updatedAt") java.time.Instant updatedAt);

    /** 主动恢复取得 Checkpoint 后退出 RESUMING 瞬时态，再执行可能耗时较长的 Graph。 */
    @Update("""
            update ai_workflow_instance
            set status = 'RUNNING',
                lease_until = #{leaseUntil},
                state_version = state_version + 1,
                updated_at = #{updatedAt}
            where workflow_instance_id = #{workflowInstanceId}
              and status = 'RESUMING'
              and execution_owner = #{executionOwner}
              and execution_fence_token = #{executionFenceToken}
              and lease_until > #{updatedAt}
            """)
    int markRunningAfterResume(@Param("workflowInstanceId") String workflowInstanceId,
                               @Param("executionOwner") String executionOwner,
                               @Param("executionFenceToken") long executionFenceToken,
                               @Param("leaseUntil") java.time.Instant leaseUntil,
                               @Param("updatedAt") java.time.Instant updatedAt);

    /** 将已确认实例交还给当前执行者继续运行，并刷新执行租约。 */
    @Update("""
            update ai_workflow_instance
            set status = 'RUNNING',
                execution_owner = #{executionOwner},
                lease_until = #{leaseUntil},
                state_version = state_version + 1,
                updated_at = #{updatedAt}
            where workflow_instance_id = #{workflowInstanceId}
              and status = 'CONFIRMING'
              and execution_owner = #{executionOwner}
              and execution_fence_token = #{executionFenceToken}
              and lease_until > #{updatedAt}
            """)
    int markRunningAfterConfirmation(@Param("workflowInstanceId") String workflowInstanceId,
                                     @Param("executionOwner") String executionOwner,
                                     @Param("executionFenceToken") long executionFenceToken,
                                     @Param("leaseUntil") java.time.Instant leaseUntil,
                                     @Param("updatedAt") java.time.Instant updatedAt);

    /** 释放已过期但未真正恢复的产品确认抢占。 */
    @Update("""
            update ai_workflow_instance
            set status = 'WAITING_CONFIRM',
                execution_owner = null,
                lease_until = null,
                state_version = state_version + 1,
                updated_at = #{now}
            where status = 'CONFIRMING'
              and lease_until is not null
              and lease_until <= #{now}
            """)
    int recoverExpiredConfirming(@Param("now") java.time.Instant now);

    /** 释放已过期但未开始执行的主动恢复抢占，允许再次从 Checkpoint 恢复。 */
    @Update("""
            update ai_workflow_instance
            set status = 'RUNNING',
                execution_owner = null,
                lease_until = null,
                state_version = state_version + 1,
                updated_at = #{now}
            where status = 'RESUMING'
              and lease_until is not null
              and lease_until <= #{now}
            """)
    int recoverExpiredResuming(@Param("now") java.time.Instant now);

    /**
     * 在一条数据库语句内同时刷新实例和 conversation lock。只有租约尚未过期的当前 owner
     * 可以续租，旧 owner 或已失去执行权的状态不会产生更新。
     */
    @Update("""
            update ai_workflow_instance i
            join ai_conversation_workflow_lock l
              on l.workflow_instance_id = i.workflow_instance_id
             and l.conversation_id = i.conversation_id
            set i.lease_until = #{leaseUntil},
                i.state_version = i.state_version + 1,
                i.updated_at = #{now},
                l.lease_until = #{leaseUntil},
                l.updated_at = #{now}
            where i.execution_owner = #{executionOwner}
              and i.status in ('RUNNING', 'CONFIRMING', 'RESUMING')
              and i.lease_until is not null
              and i.lease_until > #{now}
            """)
    int renewOwnedExecutionLeases(@Param("executionOwner") String executionOwner,
                                  @Param("leaseUntil") java.time.Instant leaseUntil,
                                  @Param("now") java.time.Instant now);

    /** 同一 conversation 的顶层工作流锁；主键冲突即表示前一轮仍未收口。 */
    @Insert("""
            insert into ai_conversation_workflow_lock (
                conversation_id, workflow_instance_id, request_id, lease_until, created_at, updated_at
            ) values (
                #{conversationId}, #{workflowInstanceId}, #{requestId}, #{leaseUntil}, #{createdAt}, #{createdAt}
            )
            """)
    void insertConversationLock(@Param("conversationId") String conversationId,
                                @Param("workflowInstanceId") String workflowInstanceId,
                                @Param("requestId") String requestId,
                                @Param("leaseUntil") java.time.Instant leaseUntil,
                                @Param("createdAt") java.time.Instant createdAt);

    /**
     * 启动新工作流前，仅回收当前 conversation 已过期且不再由有效执行或可恢复 Checkpoint
     * 保护的锁。WAITING_CONFIRM 的锁以自身 lease_until 作为确认有效期。
     */
    @Delete("""
            delete l
            from ai_conversation_workflow_lock l
            left join ai_workflow_instance i
              on i.workflow_instance_id = l.workflow_instance_id
            where l.conversation_id = #{conversationId}
              and l.lease_until <= #{now}
              and (
                  i.workflow_instance_id is null
                  or i.status in ('SUCCESS', 'PARTIAL_SUCCESS', 'REVIEW_BLOCKED', 'FAILED')
                  or i.status = 'WAITING_CONFIRM'
                  or (
                      (i.execution_owner is null or i.lease_until is null or i.lease_until <= #{now})
                      and not exists (
                          select 1
                          from ai_graph_thread t
                          where t.workflow_instance_id = i.workflow_instance_id
                            and t.expires_at > #{now}
                      )
                  )
              )
            """)
    int deleteExpiredInvalidConversationLock(@Param("conversationId") String conversationId,
                                             @Param("now") java.time.Instant now);

    /** 定时批量物理删除所有已过期且失效的 conversation workflow lock。 */
    @Delete("""
            delete l
            from ai_conversation_workflow_lock l
            left join ai_workflow_instance i
              on i.workflow_instance_id = l.workflow_instance_id
            where l.lease_until <= #{now}
              and (
                  i.workflow_instance_id is null
                  or i.status in ('SUCCESS', 'PARTIAL_SUCCESS', 'REVIEW_BLOCKED', 'FAILED')
                  or i.status = 'WAITING_CONFIRM'
                  or (
                      (i.execution_owner is null or i.lease_until is null or i.lease_until <= #{now})
                      and not exists (
                          select 1
                          from ai_graph_thread t
                          where t.workflow_instance_id = i.workflow_instance_id
                            and t.expires_at > #{now}
                      )
                  )
              )
            """)
    int deleteExpiredInvalidConversationLocks(@Param("now") java.time.Instant now);

    /** 等待人工确认时延长会话独占，避免下一轮覆盖尚未完成的 Memory 上下文。 */
    @Update("""
            update ai_conversation_workflow_lock
            set lease_until = #{leaseUntil},
                updated_at = #{updatedAt}
            where workflow_instance_id = #{workflowInstanceId}
              and exists (
                  select 1 from ai_workflow_instance i
                  where i.workflow_instance_id = ai_conversation_workflow_lock.workflow_instance_id
                    and i.status = 'WAITING_CONFIRM'
                    and i.execution_fence_token = #{executionFenceToken}
              )
            """)
    int renewConversationLock(@Param("workflowInstanceId") String workflowInstanceId,
                              @Param("executionFenceToken") long executionFenceToken,
                              @Param("leaseUntil") java.time.Instant leaseUntil,
                              @Param("updatedAt") java.time.Instant updatedAt);

    /** 仅由持有者在终态事务内释放 conversation 顶层执行权。 */
    @Delete("""
            delete from ai_conversation_workflow_lock
            where workflow_instance_id = #{workflowInstanceId}
            """)
    int deleteConversationLock(@Param("workflowInstanceId") String workflowInstanceId);

    @Insert("""
            insert into ai_workflow_step (
                workflow_step_id,
                workflow_instance_id,
                step_code,
                step_name,
                step_type,
                target,
                status,
                input_json,
                started_at,
                created_at,
                updated_at
            ) values (
                #{workflowStepId},
                #{workflowInstanceId},
                #{stepCode},
                #{stepName},
                #{stepType},
                #{target},
                #{status},
                #{inputJson},
                #{startedAt},
                #{createdAt},
                #{createdAt}
            )
            """)
    void insertStep(WorkflowStepRecord record);

    @Update("""
            update ai_workflow_step
            set status = 'RUNNING',
                started_at = #{startedAt},
                updated_at = #{startedAt}
            where workflow_step_id = #{workflowStepId}
              and exists (
                  select 1 from ai_workflow_instance i
                  where i.workflow_instance_id = ai_workflow_step.workflow_instance_id
                    and i.execution_owner = #{executionOwner}
                    and i.execution_fence_token = #{executionFenceToken}
                    and i.lease_until > #{startedAt}
                    and i.status in ('RUNNING', 'CONFIRMING', 'RESUMING')
              )
            """)
    int updateStepStarted(@Param("workflowStepId") String workflowStepId,
                          @Param("executionOwner") String executionOwner,
                          @Param("executionFenceToken") long executionFenceToken,
                          @Param("startedAt") java.time.Instant startedAt);

    @Update("""
            update ai_workflow_step
            set status = #{status},
                output_json = #{outputJson},
                error_message = #{errorMessage},
                ended_at = #{endedAt},
                updated_at = #{endedAt}
            where workflow_step_id = #{workflowStepId}
              and exists (
                  select 1 from ai_workflow_instance i
                  where i.workflow_instance_id = ai_workflow_step.workflow_instance_id
                    and i.execution_owner = #{executionOwner}
                    and i.execution_fence_token = #{executionFenceToken}
                    and i.lease_until > #{endedAt}
                    and i.status in ('RUNNING', 'CONFIRMING', 'RESUMING')
              )
            """)
    int updateStepResult(@Param("workflowStepId") String workflowStepId,
                         @Param("status") String status,
                         @Param("outputJson") String outputJson,
                         @Param("errorMessage") String errorMessage,
                         @Param("executionOwner") String executionOwner,
                         @Param("executionFenceToken") long executionFenceToken,
                         @Param("endedAt") java.time.Instant endedAt);

    @Update("""
            update ai_workflow_step
            set status = 'WAITING_CONFIRM',
                output_json = #{outputJson},
                updated_at = #{updatedAt}
            where workflow_step_id = #{workflowStepId}
              and exists (
                  select 1 from ai_workflow_instance i
                  where i.workflow_instance_id = ai_workflow_step.workflow_instance_id
                    and i.execution_owner = #{executionOwner}
                    and i.execution_fence_token = #{executionFenceToken}
                    and i.lease_until > #{updatedAt}
                    and i.status in ('RUNNING', 'CONFIRMING', 'RESUMING')
              )
            """)
    int updateStepWaitingConfirm(@Param("workflowStepId") String workflowStepId,
                                 @Param("outputJson") String outputJson,
                                 @Param("executionOwner") String executionOwner,
                                 @Param("executionFenceToken") long executionFenceToken,
                                 @Param("updatedAt") java.time.Instant updatedAt);

    @Update("""
            update ai_workflow_step
            set status = 'SKIPPED',
                ended_at = #{endedAt},
                updated_at = #{endedAt}
            where workflow_instance_id = #{workflowInstanceId}
              and status = 'PENDING'
            """)
    void skipPendingSteps(@Param("workflowInstanceId") String workflowInstanceId,
                          @Param("endedAt") java.time.Instant endedAt);
}
