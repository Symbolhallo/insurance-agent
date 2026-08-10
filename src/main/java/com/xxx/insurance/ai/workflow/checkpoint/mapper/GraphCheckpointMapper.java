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
            ) values (
                #{threadId},
                #{workflowInstanceId},
                #{conversationId},
                'ACTIVE',
                0,
                #{expiresAt},
                #{now},
                #{now}
            ) on duplicate key update
                thread_id = values(thread_id)
            """)
    int insertThreadIfAbsent(@Param("threadId") String threadId,
                             @Param("workflowInstanceId") String workflowInstanceId,
                             @Param("conversationId") String conversationId,
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
            update ai_graph_thread
            set latest_checkpoint_id = #{checkpointId},
                version = version + 1,
                expires_at = #{expiresAt},
                updated_at = #{now}
            where thread_id = #{threadId}
              and status = 'ACTIVE'
              and version = #{expectedVersion}
            """)
    int advanceThreadVersion(@Param("threadId") String threadId,
                             @Param("expectedVersion") long expectedVersion,
                             @Param("checkpointId") String checkpointId,
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
            update ai_graph_thread
            set status = #{status},
                expires_at = #{expiresAt},
                updated_at = #{now}
            where workflow_instance_id = #{workflowInstanceId}
              and status <> 'RELEASED'
            """)
    int updateWorkflowThreadStatuses(@Param("workflowInstanceId") String workflowInstanceId,
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
