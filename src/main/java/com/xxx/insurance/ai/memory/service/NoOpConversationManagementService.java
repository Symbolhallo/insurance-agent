package com.xxx.insurance.ai.memory.service;

import com.xxx.insurance.ai.memory.model.ConversationListItem;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

/** 未启用 local-db 时提供空会话列表，保持默认 Profile 可以正常启动。 */
@Service
@Profile("!local-db")
public class NoOpConversationManagementService implements ConversationManagementService {

    @Override
    public List<ConversationListItem> listConversations(int limit) {
        return List.of();
    }

    @Override
    public boolean archiveConversation(String conversationId) {
        return false;
    }
}
