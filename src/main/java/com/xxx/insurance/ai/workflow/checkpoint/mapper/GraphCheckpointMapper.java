package com.xxx.insurance.ai.workflow.checkpoint.mapper;

import com.xxx.insurance.ai.workflow.checkpoint.model.GraphCheckpointRecord;
import com.xxx.insurance.ai.workflow.checkpoint.model.GraphCheckpointThreadRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/**
 * OceanBase Graph Checkpoint 持久化 Mapper。
 */
@Mapper
public interface GraphCheckpointMapper {

    /**
     * 在当前工作流 owner、fencing token 和租约均有效时按 threadId 幂等创建 Graph Thread。
     * 重复调用只保留原记录。受 MySQL affected-rows 语义影响，返回 0 既可能表示 Thread 已存在，
     * 也可能表示执行权校验失败；调用方必须继续读取 Thread 并通过后续版本 CAS 作最终判断。
     *
     * @return SQL 影响行数
     */
    @Insert("""
            insert into ai_graph_thread (
                thread_id,
                workflow_instance_id,
                conversation_id,
                status,
                version,
                expires_at,
                created_at,
                updated_at
            ) select
                #{threadId},
                i.workflow_instance_id,
                i.conversation_id,
                'ACTIVE',
                0,
                #{expiresAt},
                #{now},
                #{now}
            from ai_workflow_instance i
            where i.workflow_instance_id = #{workflowInstanceId}
              and i.conversation_id = #{conversationId}
              and i.execution_owner = #{executionOwner}
              and i.execution_fence_token = #{executionFenceToken}
              and i.lease_until > #{now}
              and i.status in ('RUNNING', 'CONFIRMING', 'RESUMING')
            on duplicate key update
                thread_id = values(thread_id)
            """)
    int insertThreadIfAbsent(@Param("threadId") String threadId,
                             @Param("workflowInstanceId") String workflowInstanceId,
                             @Param("conversationId") String conversationId,
                             @Param("executionOwner") String executionOwner,
                             @Param("executionFenceToken") long executionFenceToken,
                             @Param("expiresAt") Instant expiresAt,
                             @Param("now") Instant now);

    /**
     * 读取尚未释放且未过期的 Thread 元数据，作为 Checkpoint 恢复和乐观锁版本基线。
     *
     * @return 不存在、已释放或已过期时返回 {@code null}
     */
    @Select("""
            select thread_id,
                   workflow_instance_id,
                   conversation_id,
                   status,
                   latest_checkpoint_id,
                   version,
                   expires_at,
                   released_at,
                   created_at,
                   updated_at
            from ai_graph_thread
            where thread_id = #{threadId}
              and status <> 'RELEASED'
              and expires_at > #{now}
            """)
    GraphCheckpointThreadRecord findReadableThread(@Param("threadId") String threadId,
                                                   @Param("now") Instant now);

    /**
     * 以 expectedVersion 乐观锁推进 Thread 版本和最新 Checkpoint 指针，同时校验父工作流的
     * owner、fencing token 与租约。只有该更新成功后才允许插入对应状态快照。
     *
     * @return 1 表示取得本版本写入权；0 表示版本冲突或执行权已失效
     */
    @Update("""
            update ai_graph_thread t
            join ai_workflow_instance i
              on i.workflow_instance_id = t.workflow_instance_id
            set t.latest_checkpoint_id = #{checkpointId},
                t.version = t.version + 1,
                t.expires_at = #{expiresAt},
                t.updated_at = #{now}
            where t.thread_id = #{threadId}
              and t.status = 'ACTIVE'
              and t.version = #{expectedVersion}
              and i.execution_owner = #{executionOwner}
              and i.execution_fence_token = #{executionFenceToken}
              and i.lease_until > #{now}
              and i.status in ('RUNNING', 'CONFIRMING', 'RESUMING')
            """)
    int advanceThreadVersion(@Param("threadId") String threadId,
                             @Param("expectedVersion") long expectedVersion,
                             @Param("checkpointId") String checkpointId,
                             @Param("executionOwner") String executionOwner,
                             @Param("executionFenceToken") long executionFenceToken,
                             @Param("expiresAt") Instant expiresAt,
                             @Param("now") Instant now);

    /**
     * 追加不可变 Checkpoint 快照。调用方须先成功推进 Thread 版本，checkpointId/版本唯一约束
     * 负责阻止同一快照重复落库。
     *
     * @return 插入行数
     */
    @Insert("""
            insert into ai_graph_checkpoint (
                checkpoint_id,
                thread_id,
                parent_checkpoint_id,
                checkpoint_version,
                node_id,
                next_node_id,
                state_payload,
                state_content_type,
                state_schema_version,
                created_at
            ) values (
                #{checkpointId},
                #{threadId},
                #{parentCheckpointId},
                #{checkpointVersion},
                #{nodeId},
                #{nextNodeId},
                #{statePayload},
                #{stateContentType},
                #{stateSchemaVersion},
                #{createdAt}
            )
            """)
    int insertCheckpoint(GraphCheckpointRecord record);

    /**
     * 在所属 Thread 仍可读且未过期时按 checkpointId 读取指定状态快照。
     *
     * @return 快照不可读时返回 {@code null}
     */
    @Select("""
            select c.checkpoint_id,
                   c.thread_id,
                   c.parent_checkpoint_id,
                   c.checkpoint_version,
                   c.node_id,
                   c.next_node_id,
                   c.state_payload,
                   c.state_content_type,
                   c.state_schema_version,
                   c.created_at
            from ai_graph_checkpoint c
            join ai_graph_thread t on t.thread_id = c.thread_id
            where c.thread_id = #{threadId}
              and c.checkpoint_id = #{checkpointId}
              and t.status <> 'RELEASED'
              and t.expires_at > #{now}
            """)
    GraphCheckpointRecord findCheckpoint(@Param("threadId") String threadId,
                                         @Param("checkpointId") String checkpointId,
                                         @Param("now") Instant now);

    /**
     * 按版本倒序列出一个有效 Thread 的全部 Checkpoint，用于历史查询与恢复选择。
     */
    @Select("""
            select c.checkpoint_id,
                   c.thread_id,
                   c.parent_checkpoint_id,
                   c.checkpoint_version,
                   c.node_id,
                   c.next_node_id,
                   c.state_payload,
                   c.state_content_type,
                   c.state_schema_version,
                   c.created_at
            from ai_graph_checkpoint c
            join ai_graph_thread t on t.thread_id = c.thread_id
            where c.thread_id = #{threadId}
              and t.status <> 'RELEASED'
              and t.expires_at > #{now}
            order by c.checkpoint_version desc
            """)
    List<GraphCheckpointRecord> findCheckpoints(@Param("threadId") String threadId,
                                                @Param("now") Instant now);

    /**
     * 更新单个未释放 Thread 的生命周期状态和到期时间。
     *
     * @return 1 表示更新成功；0 表示 Thread 不存在或已经 RELEASED
     */
    @Update("""
            update ai_graph_thread
            set status = #{status},
                expires_at = #{expiresAt},
                updated_at = #{now}
            where thread_id = #{threadId}
              and status <> 'RELEASED'
            """)
    int updateThreadStatus(@Param("threadId") String threadId,
                           @Param("status") String status,
                           @Param("expiresAt") Instant expiresAt,
                           @Param("now") Instant now);

    /**
     * 工作流进入业务终态后，按 workflowInstanceId 批量更新其主图和任务子图 Thread。
     * 父实例终态及 fencing token 校验可阻止旧执行者缩短或覆盖新一代 Checkpoint 生命周期。
     *
     * @return 更新的 Thread 数量
     */
    @Update("""
            update ai_graph_thread t
            join ai_workflow_instance i
              on i.workflow_instance_id = t.workflow_instance_id
            set t.status = #{status},
                t.expires_at = #{expiresAt},
                t.updated_at = #{now}
            where t.workflow_instance_id = #{workflowInstanceId}
              and t.status <> 'RELEASED'
              and i.execution_fence_token = #{executionFenceToken}
              and i.status in ('SUCCESS', 'PARTIAL_SUCCESS', 'REVIEW_BLOCKED', 'FAILED')
            """)
    int updateWorkflowThreadStatuses(@Param("workflowInstanceId") String workflowInstanceId,
                                     @Param("executionFenceToken") long executionFenceToken,
                                     @Param("status") String status,
                                     @Param("expiresAt") Instant expiresAt,
                                     @Param("now") Instant now);

    /**
     * 将 Thread 标记为 RELEASED 并设置短期保留时间；实际快照由后续清理任务物理删除。
     *
     * @return 1 表示首次释放；0 表示不存在或已经释放
     */
    @Update("""
            update ai_graph_thread
            set status = 'RELEASED',
                released_at = #{now},
                expires_at = #{expiresAt},
                updated_at = #{now}
            where thread_id = #{threadId}
              and status <> 'RELEASED'
            """)
    int releaseThread(@Param("threadId") String threadId,
                      @Param("expiresAt") Instant expiresAt,
                      @Param("now") Instant now);

    /**
     * 先物理删除所有已到期 Thread 下的 Checkpoint，避免随后删除 Thread 时残留快照或触发外键约束。
     *
     * @return 删除的 Checkpoint 数量
     */
    @Delete("""
            delete from ai_graph_checkpoint
            where thread_id in (
                select thread_id
                from ai_graph_thread
                where expires_at <= #{now}
            )
            """)
    int deleteExpiredCheckpoints(@Param("now") Instant now);

    /**
     * 在快照清理后物理删除已到期 Thread 元数据；未到期 Thread 不受影响。
     *
     * @return 删除的 Thread 数量
     */
    @Delete("delete from ai_graph_thread where expires_at <= #{now}")
    int deleteExpiredThreads(@Param("now") Instant now);
}
