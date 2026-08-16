package com.xxx.insurance.ai.workflow.sse.mapper;

import com.xxx.insurance.ai.workflow.sse.model.WorkflowSseEventRecord;
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

    /**
     * 通过工作流实例行原子递增执行期 SSE 序号，并用 {@code last_insert_id} 将新序号绑定到当前连接。
     * 只有持有当前 owner、fencing token 和有效租约的执行者可以分配。
     *
     * @return 1 表示分配成功；0 表示执行权已失效
     */
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

    /**
     * 收口事务写入终态后按同一 fencing token 原子分配 COMPLETE/ERROR 等最终事件序号。
     *
     * @return 1 表示分配成功；0 表示终态或 token 不匹配
     */
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

    /**
     * 人工暂停事务清除 owner 并进入 WAITING_CONFIRM 后，按本次 fencing token 分配确认事件序号。
     *
     * @return 1 表示分配成功；0 表示实例未正确暂停或 token 不匹配
     */
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

    /**
     * 读取当前数据库连接由前一条序号分配 UPDATE 写入的 {@code last_insert_id}。
     * 必须与分配语句在同一事务和连接中执行，不能单独调用。
     */
    @Select("select last_insert_id()")
    long lastAllocatedSequence();

    /**
     * 将已分配序号的 SSE 事件写入 OceanBase Outbox，作为多实例投递和 Last-Event-ID 重放的事实来源。
     */
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

    /**
     * 从指定序号之后按序读取尚未过期的事件，供首次订阅、断线重连和数据库 Poller 增量投递。
     */
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

    /**
     * 查询实例已分配的最高事件序号，用于判断订阅者是否追平数据库事件水位。
     *
     * @return 实例不存在时返回 {@code null}
     */
    @Select("""
            select event_sequence
            from ai_workflow_instance
            where workflow_instance_id = #{workflowInstanceId}
            """)
    Long findHighWatermark(@Param("workflowInstanceId") String workflowInstanceId);

    /**
     * 物理删除已超过 expire_at 的 SSE 事件；未过期事件继续支持实时投递和 Last-Event-ID 重放。
     *
     * @return 本次删除的事件数量
     */
    @Delete("delete from ai_workflow_sse_event where expire_at <= #{now}")
    int deleteExpiredEvents(@Param("now") Instant now);
}
