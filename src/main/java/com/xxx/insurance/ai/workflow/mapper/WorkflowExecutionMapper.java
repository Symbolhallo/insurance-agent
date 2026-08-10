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
                   created_at
            from ai_workflow_instance
            where workflow_instance_id = #{workflowInstanceId}
            """)
    @ConstructorArgs({
            @Arg(column = "workflow_instance_id", javaType = String.class),
            @Arg(column = "conversation_id", javaType = String.class),
            @Arg(column = "status", javaType = String.class),
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
              and status not in ('SUCCESS', 'PARTIAL_SUCCESS', 'REVIEW_BLOCKED', 'FAILED')
            """)
    int finalizeInstance(@Param("workflowInstanceId") String workflowInstanceId,
                         @Param("status") String status,
                         @Param("outputJson") String outputJson,
                         @Param("errorMessage") String errorMessage,
                         @Param("executionOwner") String executionOwner,
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
              and status not in ('SUCCESS', 'PARTIAL_SUCCESS', 'REVIEW_BLOCKED', 'FAILED')
            """)
    int failInstanceIfNonTerminal(@Param("workflowInstanceId") String workflowInstanceId,
                                  @Param("errorMessage") String errorMessage,
                                  @Param("executionOwner") String executionOwner,
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
            """)
    int updateInstanceStatus(@Param("workflowInstanceId") String workflowInstanceId,
                             @Param("status") String status,
                             @Param("outputJson") String outputJson,
                             @Param("executionOwner") String executionOwner,
                             @Param("updatedAt") java.time.Instant updatedAt);

    /** 原子抢占等待产品确认的实例，防止多个确认请求从同一 Checkpoint 重复恢复。 */
    @Update("""
            update ai_workflow_instance
            set status = 'CONFIRMING',
                error_message = null,
                execution_owner = #{executionOwner},
                lease_until = #{leaseUntil},
                state_version = state_version + 1,
                updated_at = #{updatedAt}
            where workflow_instance_id = #{workflowInstanceId}
              and conversation_id = #{conversationId}
              and status = 'WAITING_CONFIRM'
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
            """)
    int releaseProductConfirmationClaim(@Param("workflowInstanceId") String workflowInstanceId,
                                        @Param("conversationId") String conversationId,
                                        @Param("executionOwner") String executionOwner,
                                        @Param("updatedAt") java.time.Instant updatedAt);

    @Update("""
            update ai_workflow_instance
            set status = 'RESUMING',
                error_message = null,
                execution_owner = #{executionOwner},
                lease_until = #{leaseUntil},
                state_version = state_version + 1,
                updated_at = #{updatedAt}
            where workflow_instance_id = #{workflowInstanceId}
              and conversation_id = #{conversationId}
              and status = 'RUNNING'
              and (execution_owner is null or lease_until is null or lease_until <= #{updatedAt})
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
            """)
    int markRunningAfterResume(@Param("workflowInstanceId") String workflowInstanceId,
                               @Param("executionOwner") String executionOwner,
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
            """)
    int markRunningAfterConfirmation(@Param("workflowInstanceId") String workflowInstanceId,
                                     @Param("executionOwner") String executionOwner,
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

    /** 等待人工确认时延长会话独占，避免下一轮覆盖尚未完成的 Memory 上下文。 */
    @Update("""
            update ai_conversation_workflow_lock
            set lease_until = #{leaseUntil},
                updated_at = #{updatedAt}
            where workflow_instance_id = #{workflowInstanceId}
            """)
    int renewConversationLock(@Param("workflowInstanceId") String workflowInstanceId,
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
            """)
    void updateStepStarted(@Param("workflowStepId") String workflowStepId,
                           @Param("startedAt") java.time.Instant startedAt);

    @Update("""
            update ai_workflow_step
            set status = #{status},
                output_json = #{outputJson},
                error_message = #{errorMessage},
                ended_at = #{endedAt},
                updated_at = #{endedAt}
            where workflow_step_id = #{workflowStepId}
            """)
    void updateStepResult(@Param("workflowStepId") String workflowStepId,
                          @Param("status") String status,
                          @Param("outputJson") String outputJson,
                          @Param("errorMessage") String errorMessage,
                          @Param("endedAt") java.time.Instant endedAt);

    @Update("""
            update ai_workflow_step
            set status = 'WAITING_CONFIRM',
                output_json = #{outputJson},
                updated_at = #{updatedAt}
            where workflow_step_id = #{workflowStepId}
            """)
    void updateStepWaitingConfirm(@Param("workflowStepId") String workflowStepId,
                                  @Param("outputJson") String outputJson,
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
