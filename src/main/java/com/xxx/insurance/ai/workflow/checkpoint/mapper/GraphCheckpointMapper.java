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

    @Delete("""
            delete from ai_graph_checkpoint
            where thread_id in (
                select thread_id
                from ai_graph_thread
                where expires_at <= #{now}
            )
            """)
    int deleteExpiredCheckpoints(@Param("now") Instant now);

    @Delete("delete from ai_graph_thread where expires_at <= #{now}")
    int deleteExpiredThreads(@Param("now") Instant now);
}
