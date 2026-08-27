package com.xxx.insurance.ai.memory.mapper;

import com.xxx.insurance.ai.memory.model.ConversationListItem;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/**
 * 测试台历史会话管理 Mapper。
 *
 * <p>删除操作仅归档会话主记录；聊天、长期记忆、调用流水和 Workflow 数据继续保留，满足审计与
 * 故障排查要求。正在执行或等待确认的会话不能归档。</p>
 */
@Mapper
public interface ConversationManagementMapper {

    /** 按最近更新时间倒序列出未删除会话，并统计完整长期消息数量。 */
    @Select("""
            select c.conversation_id,
                   c.title,
                   c.agent_name,
                   coalesce(m.message_count, 0) as message_count,
                   c.updated_at
            from ai_conversation c
            left join (
                select conversation_id,
                       count(*) as message_count
                from ai_long_term_memory
                where memory_type = 'MESSAGE'
                group by conversation_id
            ) m on m.conversation_id = c.conversation_id
            where c.status <> 'DELETED'
            order by c.updated_at desc, c.conversation_id asc
            limit #{limit}
            """)
    @ConstructorArgs({
            @Arg(column = "conversation_id", javaType = String.class),
            @Arg(column = "title", javaType = String.class),
            @Arg(column = "agent_name", javaType = String.class),
            @Arg(column = "message_count", javaType = long.class),
            @Arg(column = "updated_at", javaType = Instant.class)
    })
    List<ConversationListItem> findActiveConversations(@Param("limit") int limit);

    /**
     * 将不再被活跃工作流占用的会话标记为已删除。SQL 同时校验实例状态与有效 conversation lock，
     * 防止测试页面删除仍在运行、恢复或等待人工确认的会话入口。
     */
    @Update("""
            update ai_conversation
            set status = 'DELETED',
                updated_at = #{deletedAt}
            where conversation_id = #{conversationId}
              and status <> 'DELETED'
              and not exists (
                  select 1
                  from ai_workflow_instance i
                  where i.conversation_id = ai_conversation.conversation_id
                    and i.status in ('RUNNING', 'CONFIRMING', 'RESUMING', 'WAITING_CONFIRM')
              )
              and not exists (
                  select 1
                  from ai_conversation_workflow_lock l
                  where l.conversation_id = ai_conversation.conversation_id
                    and l.lease_until > #{deletedAt}
              )
            """)
    int archiveConversation(@Param("conversationId") String conversationId,
                            @Param("deletedAt") Instant deletedAt);

    /** 查询会话是否仍被活跃实例或有效租约占用，用于区分幂等删除与状态冲突。 */
    @Select("""
            select count(*)
            from ai_conversation c
            where c.conversation_id = #{conversationId}
              and c.status <> 'DELETED'
              and (
                  exists (
                      select 1
                      from ai_workflow_instance i
                      where i.conversation_id = c.conversation_id
                        and i.status in ('RUNNING', 'CONFIRMING', 'RESUMING', 'WAITING_CONFIRM')
                  )
                  or exists (
                      select 1
                      from ai_conversation_workflow_lock l
                      where l.conversation_id = c.conversation_id
                        and l.lease_until > #{now}
                  )
              )
            """)
    int countActiveUsage(@Param("conversationId") String conversationId,
                         @Param("now") Instant now);
}
