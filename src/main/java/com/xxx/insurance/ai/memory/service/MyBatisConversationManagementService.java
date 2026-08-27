package com.xxx.insurance.ai.memory.service;

import com.xxx.insurance.ai.memory.mapper.ConversationManagementMapper;
import com.xxx.insurance.ai.memory.model.ConversationListItem;
import com.xxx.insurance.common.exception.BusinessException;
import com.xxx.insurance.common.exception.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** OceanBase 会话列表和软删除实现。 */
@Service
@Profile("local-db")
public class MyBatisConversationManagementService implements ConversationManagementService {

    private static final int DEFAULT_LIMIT = 50;

    private static final int MAX_LIMIT = 100;

    private final ConversationManagementMapper conversationManagementMapper;

    /** 创建会话管理服务，所有数据库操作集中由 MyBatis Mapper 承担。 */
    public MyBatisConversationManagementService(ConversationManagementMapper conversationManagementMapper) {
        this.conversationManagementMapper = conversationManagementMapper;
    }

    /** 对列表条数执行服务端上限保护后，按最近更新时间返回有效会话。 */
    @Override
    public List<ConversationListItem> listConversations(int limit) {
        int queryLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        return List.copyOf(conversationManagementMapper.findActiveConversations(queryLimit));
    }

    /**
     * 先以单条条件 UPDATE 尝试归档；更新失败时再检查是否仍被有效 Workflow/Lease 占用。忙碌会话返回
     * 409，已经删除或不存在的会话按幂等成功处理，长期记忆和审计记录始终保留。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean archiveConversation(String conversationId) {
        Instant now = Instant.now();
        if (conversationManagementMapper.archiveConversation(conversationId, now) == 1) {
            return true;
        }
        if (conversationManagementMapper.countActiveUsage(conversationId, now) > 0) {
            throw new BusinessException(
                    ErrorCode.WORKFLOW_STATE_CONFLICT,
                    "conversation is still owned by an active workflow");
        }
        return false;
    }
}
