package com.xxx.insurance.ai.agent.state;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** OceanBase AgentScope 状态持久化 Mapper。 */
@Mapper
public interface AgentScopeStateMapper {

    /** 无条件新增或覆盖状态，并在覆盖时递增版本。 */
    @Insert("""
            insert into ai_agentscope_state (
                user_id, session_id, state_key, state_payload, list_value, state_version
            ) values (
                #{userId}, #{sessionId}, #{stateKey}, #{statePayload}, #{listValue}, 1
            )
            on duplicate key update
                state_payload = values(state_payload),
                list_value = values(list_value),
                state_version = state_version + 1,
                updated_at = current_timestamp
            """)
    int upsert(@Param("userId") String userId,
               @Param("sessionId") String sessionId,
               @Param("stateKey") String stateKey,
               @Param("statePayload") String statePayload,
               @Param("listValue") boolean listValue);

    /** expectedVersion=0 时仅在状态键不存在时创建，供 AgentScope CAS 首次写入。 */
    @Insert("""
            insert ignore into ai_agentscope_state (
                user_id, session_id, state_key, state_payload, list_value, state_version
            ) values (
                #{userId}, #{sessionId}, #{stateKey}, #{statePayload}, 0, 1
            )
            """)
    int insertIfAbsent(@Param("userId") String userId,
                       @Param("sessionId") String sessionId,
                       @Param("stateKey") String stateKey,
                       @Param("statePayload") String statePayload);

    /** 仅当数据库版本仍等于调用方已读版本时覆盖单值状态并递增版本。 */
    @Update("""
            update ai_agentscope_state
            set state_payload = #{statePayload},
                list_value = 0,
                state_version = state_version + 1,
                updated_at = current_timestamp
            where user_id = #{userId}
              and session_id = #{sessionId}
              and state_key = #{stateKey}
              and state_version = #{expectedVersion}
            """)
    int updateIfVersion(@Param("userId") String userId,
                        @Param("sessionId") String sessionId,
                        @Param("stateKey") String stateKey,
                        @Param("statePayload") String statePayload,
                        @Param("expectedVersion") long expectedVersion);

    /** 读取一个状态键的载荷类型和版本。 */
    @Select("""
            select state_payload, list_value, state_version
            from ai_agentscope_state
            where user_id = #{userId}
              and session_id = #{sessionId}
              and state_key = #{stateKey}
            """)
    @ConstructorArgs({
            @Arg(column = "state_payload", javaType = String.class),
            @Arg(column = "list_value", javaType = boolean.class),
            @Arg(column = "state_version", javaType = long.class)
    })
    AgentScopeStateRecord find(@Param("userId") String userId,
                               @Param("sessionId") String sessionId,
                               @Param("stateKey") String stateKey);

    /** 判断指定 AgentScope 会话是否至少存在一个状态键。 */
    @Select("""
            select count(1)
            from ai_agentscope_state
            where user_id = #{userId}
              and session_id = #{sessionId}
            """)
    int countSession(@Param("userId") String userId, @Param("sessionId") String sessionId);

    /** 物理删除一个 AgentScope 会话的全部状态。 */
    @Delete("""
            delete from ai_agentscope_state
            where user_id = #{userId}
              and session_id = #{sessionId}
            """)
    int deleteSession(@Param("userId") String userId, @Param("sessionId") String sessionId);

    /** 物理删除一个会话内的单个状态键。 */
    @Delete("""
            delete from ai_agentscope_state
            where user_id = #{userId}
              and session_id = #{sessionId}
              and state_key = #{stateKey}
            """)
    int deleteState(@Param("userId") String userId,
                    @Param("sessionId") String sessionId,
                    @Param("stateKey") String stateKey);

    /** 列出一个用户命名空间下的 AgentScope 会话编号。 */
    @Select("""
            select distinct session_id
            from ai_agentscope_state
            where user_id = #{userId}
            order by session_id
            """)
    List<String> findSessionIds(@Param("userId") String userId);
}

