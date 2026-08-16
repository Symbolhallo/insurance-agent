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

    /**
     * 读取工作流实例当前的状态、执行租约和 fencing token，供恢复、确认和写入权限判断使用。
     *
     * @return 实例不存在时返回 {@code null}
     */
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

    /**
     * 创建顶层工作流实例，并将初始 owner、租约、fencing token 和状态版本一并落库。
     * workflowInstanceId 及 conversationId/requestId 的唯一约束同时承担执行幂等保护。
     */
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
     * 原子写入正常业务终态并释放执行租约。只有仍持有有效 owner、fencing token 和租约的执行者
     * 可以收口；终态保护条件保证重复收口或迟到异常不能覆盖既有结果。
     *
     * @return 1 表示成功收口；0 表示执行权已失效或实例已经进入终态
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

    /**
     * 在当前执行者仍持有有效租约时将非终态实例收口为 FAILED，并释放执行权。
     * SUCCESS、PARTIAL_SUCCESS、REVIEW_BLOCKED 等终态不会被迟到异常覆盖。
     *
     * @return 1 表示失败终态已写入；0 表示执行权已失效或实例已经是终态
     */
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

    /**
     * 在 owner、fencing token 和租约校验通过后更新实例状态，并释放当前执行权。
     * 该方法用于受控的非标准状态迁移；调用方必须检查返回值，0 表示当前执行者无权写入。
     *
     * @return 实际更新的实例行数
     */
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

    /**
     * 将 WAITING_CONFIRM 原子抢占为 CONFIRMING，并生成新一代 fencing token。
     * conversation lock 仍有效才允许抢占，从数据库层阻止并发确认请求恢复同一 Checkpoint。
     *
     * @return 1 表示抢占成功；0 表示状态、会话锁或并发条件不满足
     */
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

    /**
     * 后台恢复任务尚未提交时，将当前 owner 抢占的 CONFIRMING 回退为 WAITING_CONFIRM。
     * owner 与 fencing token 条件可防止旧请求释放新执行者的确认权。
     *
     * @return 1 表示释放成功；0 表示抢占权已变化
     */
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

    /**
     * 原子抢占无人持有或租约已过期的 RUNNING 实例，将其切换为 RESUMING 并递增 fencing token。
     * 仍要求 conversation lock 存在，避免恢复一个已经脱离顶层会话互斥边界的实例。
     *
     * @return 1 表示取得恢复权；0 表示实例不可恢复或已被其他执行者抢占
     */
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

    /**
     * 当前恢复者取得 Checkpoint 后将 RESUMING 切回 RUNNING，并续长租约后执行 Graph。
     *
     * @return 1 表示迁移成功；0 表示 owner、fencing token 或租约已失效
     */
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

    /**
     * 产品确认数据保存完成后，将当前 owner 的 CONFIRMING 实例切回 RUNNING 并刷新租约。
     *
     * @return 1 表示迁移成功；0 表示确认执行权已失效
     */
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

    /**
     * 批量回收租约已过期的 CONFIRMING 瞬时态，使用户可以重新提交产品确认。
     * 未过期记录不会被修改，多实例可依赖数据库 UPDATE 的行锁安全并发执行。
     *
     * @return 本次回收的实例数
     */
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

    /**
     * 批量回收租约已过期的 RESUMING 瞬时态，清除 owner 后允许其他实例再次恢复。
     *
     * @return 本次回收的实例数
     */
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
     *
     * @return 本次成功续租的工作流数量；0 表示该 owner 当前没有可续租实例
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

    /**
     * 创建同一 conversation 的顶层工作流互斥锁。conversationId 主键冲突表示前一轮仍未收口，
     * requestId 唯一约束同时阻止网关重试或用户重复提交创建第二个实例。
     */
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
     *
     * @return 删除的锁行数，通常为 0 或 1
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

    /**
     * 定时物理删除所有已过期且失效的 conversation workflow lock。有效执行租约或尚未过期的
     * Graph Thread 会阻止删除，保证清理任务不会释放仍可运行或恢复的会话。
     *
     * @return 本次删除的锁数量
     */
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

    /**
     * 工作流进入 WAITING_CONFIRM 后按当前 fencing token 延长 conversation lock，避免人工等待期间
     * 新一轮工作流覆盖尚未完成的 Memory 上下文。
     *
     * @return 1 表示续期成功；0 表示实例已离开等待态或 token 已变化
     */
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

    /**
     * 在工作流终态事务内按 workflowInstanceId 释放 conversation 顶层执行权。
     *
     * @return 删除的锁行数；幂等重试时可能为 0
     */
    @Delete("""
            delete from ai_conversation_workflow_lock
            where workflow_instance_id = #{workflowInstanceId}
            """)
    int deleteConversationLock(@Param("workflowInstanceId") String workflowInstanceId);

    /**
     * 创建一个工作流节点执行记录，保存节点输入及初始状态，供审计和故障排查使用。
     */
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

    /**
     * 将步骤切换为 RUNNING。EXISTS 子查询同时校验父实例的 owner、fencing token、租约和运行状态，
     * 防止已经失去执行权的旧 Graph 继续写步骤审计。
     *
     * @return 1 表示步骤开始状态已写入；0 表示步骤不存在或父实例执行权已失效
     */
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

    /**
     * 保存步骤终态、输出或错误信息。写入前通过父实例校验当前执行者的 Lease/Fence 权限。
     *
     * @return 1 表示结果已写入；0 表示步骤不存在或当前执行者已被 fencing
     */
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

    /**
     * 将人工确认节点切换为 WAITING_CONFIRM 并保存候选产品等暂停输出。
     * EXISTS 子查询把步骤写入绑定到父工作流当前有效的 owner、fencing token 和租约；返回 0 时
     * 调用方必须终止暂停流程，不能继续写 Checkpoint 或发送确认事件。
     *
     * @return 1 表示等待确认状态已写入；0 表示步骤不存在或父实例执行权已失效
     */
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

    /**
     * 工作流收口时将仍为 PENDING 的未执行步骤批量标记为 SKIPPED，保留完整执行历史。
     * 已经运行或已有结果的步骤不会被覆盖。
     */
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
