package com.xxx.insurance.ai.workflow.mapper;

import com.xxx.insurance.ai.workflow.model.WorkflowSseEventRecord;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/**
 * 工作流 SSE 序号分配、事件写入和重放查询 Mapper。
 */
@Mapper
public interface WorkflowSseEventMapper {

    /** 只有持有当前有效 execution lease 的执行者才能分配执行期事件序号。 */
    @Update("""
            update ai_workflow_instance
            set event_sequence = last_insert_id(event_sequence + 1),
                updated_at = updated_at
            where workflow_instance_id = #{workflowInstanceId}
              and execution_owner = #{executionOwner}
              and execution_fence_token = #{executionFenceToken}
              and lease_until > #{now}
              and status in ('RUNNING', 'CONFIRMING', 'RESUMING')
            """)
    int allocateExecutionSequence(@Param("workflowInstanceId") String workflowInstanceId,
                                  @Param("executionOwner") String executionOwner,
                                  @Param("executionFenceToken") long executionFenceToken,
                                  @Param("now") Instant now);

    /** 收口事务获得终态写入权后，按同一 fencing token 分配最终事件序号。 */
    @Update("""
            update ai_workflow_instance
            set event_sequence = last_insert_id(event_sequence + 1),
                updated_at = updated_at
            where workflow_instance_id = #{workflowInstanceId}
              and execution_fence_token = #{executionFenceToken}
              and status in ('SUCCESS', 'PARTIAL_SUCCESS', 'REVIEW_BLOCKED', 'FAILED')
            """)
    int allocateTerminalSequence(@Param("workflowInstanceId") String workflowInstanceId,
                                 @Param("executionFenceToken") long executionFenceToken);

    /** 人工暂停事务完成状态切换后，仍以本次执行 token 分配确认事件序号。 */
    @Update("""
            update ai_workflow_instance
            set event_sequence = last_insert_id(event_sequence + 1),
                updated_at = updated_at
            where workflow_instance_id = #{workflowInstanceId}
              and execution_fence_token = #{executionFenceToken}
              and status = 'WAITING_CONFIRM'
              and execution_owner is null
            """)
    int allocateWaitingConfirmSequence(@Param("workflowInstanceId") String workflowInstanceId,
                                       @Param("executionFenceToken") long executionFenceToken);

    /** 读取当前事务连接刚刚分配的工作流事件序号。 */
    @Select("select last_insert_id()")
    long lastAllocatedSequence();

    /** 写入一条可重放 SSE 事件。 */
    @Insert("""
            insert into ai_workflow_sse_event (
                event_id, workflow_instance_id, conversation_id, sequence_no, event_type,
                node_code, payload_json, created_at, expire_at
            ) values (
                #{eventId}, #{workflowInstanceId}, #{conversationId}, #{sequenceNo}, #{eventType},
                #{nodeCode}, #{payloadJson}, #{createdAt}, #{expireAt}
            )
            """)
    void insert(WorkflowSseEventRecord record);

    /** 按序号升序读取尚未过期的重放事件。 */
    @Select("""
            select event_id, workflow_instance_id, conversation_id, sequence_no, event_type,
                   node_code, payload_json, created_at, expire_at
            from ai_workflow_sse_event
            where workflow_instance_id = #{workflowInstanceId}
              and sequence_no > #{afterSequence}
              and expire_at > #{now}
            order by sequence_no asc
            """)
    @ConstructorArgs({
            @Arg(column = "event_id", javaType = String.class),
            @Arg(column = "workflow_instance_id", javaType = String.class),
            @Arg(column = "conversation_id", javaType = String.class),
            @Arg(column = "sequence_no", javaType = long.class),
            @Arg(column = "event_type", javaType = String.class),
            @Arg(column = "node_code", javaType = String.class),
            @Arg(column = "payload_json", javaType = String.class),
            @Arg(column = "created_at", javaType = Instant.class),
            @Arg(column = "expire_at", javaType = Instant.class)
    })
    List<WorkflowSseEventRecord> findReplayEvents(@Param("workflowInstanceId") String workflowInstanceId,
                                                  @Param("afterSequence") long afterSequence,
                                                  @Param("now") Instant now);

    /** 查询当前实例已经分配的最高事件序号。 */
    @Select("""
            select event_sequence
            from ai_workflow_instance
            where workflow_instance_id = #{workflowInstanceId}
            """)
    Long findHighWatermark(@Param("workflowInstanceId") String workflowInstanceId);

    /** 删除已超过重放保留期的 SSE 事件。 */
    @Delete("delete from ai_workflow_sse_event where expire_at <= #{now}")
    int deleteExpiredEvents(@Param("now") Instant now);
}
