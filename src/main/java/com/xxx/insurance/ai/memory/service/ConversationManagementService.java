package com.xxx.insurance.ai.memory.service;

import com.xxx.insurance.ai.memory.model.ConversationListItem;

import java.util.List;

/** 历史会话列表和归档管理边界。 */
public interface ConversationManagementService {

    /** 列出最近使用的有效会话。 */
    List<ConversationListItem> listConversations(int limit);

    /**
     * 从测试台会话列表归档指定会话；持久化消息和审计数据不会物理删除。
     *
     * @return {@code true} 表示本次完成归档，{@code false} 表示会话不存在或已经归档
     */
    boolean archiveConversation(String conversationId);
}
