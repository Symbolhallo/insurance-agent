package com.xxx.insurance.ai.workflow.checkpoint;

import com.xxx.insurance.ai.workflow.checkpoint.mapper.GraphCheckpointMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GraphCheckpointMapperRetentionTests {

    @Test
    void deletesOnlyExpiredCheckpointsBeforeDeletingExpiredThreads() throws Exception {
        String checkpointSql = deleteSql("deleteExpiredCheckpoints");
        String threadSql = deleteSql("deleteExpiredThreads");

        assertThat(checkpointSql)
                .contains("where expires_at <= #{now}")
                .contains("delete from ai_graph_checkpoint");
        assertThat(threadSql)
                .contains("delete from ai_graph_thread where expires_at <= #{now}");
    }

    @Test
    void checkpointAdvanceRequiresOwnerFenceTokenAndLiveLease() throws Exception {
        String sql = String.join("\n", GraphCheckpointMapper.class.getMethod(
                        "advanceThreadVersion", String.class, long.class, String.class,
                        String.class, long.class, Instant.class, Instant.class)
                .getAnnotation(Update.class).value());

        assertThat(sql)
                .contains("i.execution_owner = #{executionOwner}")
                .contains("i.execution_fence_token = #{executionFenceToken}")
                .contains("i.lease_until > #{now}")
                .contains("t.version = #{expectedVersion}");
    }

    /** 读取 Mapper 注解中的实际生产 SQL，防止后续误删未过期线程。 */
    private String deleteSql(String methodName) throws Exception {
        Delete annotation = GraphCheckpointMapper.class
                .getMethod(methodName, Instant.class)
                .getAnnotation(Delete.class);
        return String.join("\n", annotation.value()).replaceAll("\\s+", " ").trim();
    }
}
