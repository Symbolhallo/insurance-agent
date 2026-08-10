package com.xxx.insurance.ai.workflow.mapper;

import com.xxx.insurance.ai.workflow.model.WorkflowInstanceRecord;
import com.xxx.insurance.ai.workflow.model.WorkflowStepRecord;
import com.xxx.insurance.ai.workflow.model.WorkflowInstanceExecutionView;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
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
                trace_id,
                status,
                input_json,
                created_at,
                updated_at
            ) values (
                #{workflowInstanceId},
                #{workflowCode},
                #{conversationId},
                #{traceId},
                #{status},
                #{inputJson},
                #{createdAt},
                #{createdAt}
            )
            """)
    void insertInstance(WorkflowInstanceRecord record);

    @Update("""
            update ai_workflow_instance
            set status = #{status},
                output_json = #{outputJson},
                error_message = #{errorMessage},
                updated_at = #{endedAt}
            where workflow_instance_id = #{workflowInstanceId}
            """)
    void updateInstanceResult(@Param("workflowInstanceId") String workflowInstanceId,
                              @Param("status") String status,
                              @Param("outputJson") String outputJson,
                              @Param("errorMessage") String errorMessage,
                              @Param("endedAt") java.time.Instant endedAt);

    @Update("""
            update ai_workflow_instance
            set status = #{status},
                output_json = #{outputJson},
                error_message = null,
                updated_at = #{updatedAt}
            where workflow_instance_id = #{workflowInstanceId}
            """)
    void updateInstanceStatus(@Param("workflowInstanceId") String workflowInstanceId,
                              @Param("status") String status,
                              @Param("outputJson") String outputJson,
                              @Param("updatedAt") java.time.Instant updatedAt);

    /** 原子抢占等待产品确认的实例，防止多个确认请求从同一 Checkpoint 重复恢复。 */
    @Update("""
            update ai_workflow_instance
            set status = 'CONFIRMING',
                error_message = null,
                updated_at = #{updatedAt}
            where workflow_instance_id = #{workflowInstanceId}
              and conversation_id = #{conversationId}
              and status = 'WAITING_CONFIRM'
            """)
    int claimProductConfirmation(@Param("workflowInstanceId") String workflowInstanceId,
                                 @Param("conversationId") String conversationId,
                                 @Param("updatedAt") java.time.Instant updatedAt);

    /** 后台任务尚未提交时释放确认抢占，允许用户重新提交。 */
    @Update("""
            update ai_workflow_instance
            set status = 'WAITING_CONFIRM',
                updated_at = #{updatedAt}
            where workflow_instance_id = #{workflowInstanceId}
              and conversation_id = #{conversationId}
              and status = 'CONFIRMING'
            """)
    int releaseProductConfirmationClaim(@Param("workflowInstanceId") String workflowInstanceId,
                                        @Param("conversationId") String conversationId,
                                        @Param("updatedAt") java.time.Instant updatedAt);

    @Update("""
            update ai_workflow_instance
            set status = 'RESUMING',
                error_message = null,
                updated_at = #{updatedAt}
            where workflow_instance_id = #{workflowInstanceId}
              and conversation_id = #{conversationId}
              and status = 'RUNNING'
            """)
    int claimResume(@Param("workflowInstanceId") String workflowInstanceId,
                    @Param("conversationId") String conversationId,
                    @Param("updatedAt") java.time.Instant updatedAt);

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
