package com.xxx.insurance.common.security;

import com.xxx.insurance.common.exception.BusinessException;
import com.xxx.insurance.common.exception.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** local-db 下使用 OceanBase 根记录执行租户和用户级资源隔离。 */
@Service
@Profile("local-db")
public class LocalDbResourceAccessService implements ResourceAccessService {

    private final ResourceAccessMapper mapper;

    public LocalDbResourceAccessService(ResourceAccessMapper mapper) {
        this.mapper = mapper;
    }

    /** 先幂等插入所有权占位，再回读租户和用户；并发冲突不能覆盖既有 owner。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void claimConversation(RequestIdentity identity, String conversationId) {
        mapper.claimConversation(identity, conversationId, Instant.now());
        requireConversationAccess(identity, conversationId);
    }

    @Override
    public void requireConversationAccess(RequestIdentity identity, String conversationId) {
        if (mapper.countOwnedConversation(identity, conversationId) != 1) {
            throw notFound();
        }
    }

    @Override
    public void requireWorkflowAccess(RequestIdentity identity, String workflowInstanceId) {
        if (mapper.countOwnedWorkflow(identity, workflowInstanceId) != 1) {
            throw notFound();
        }
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "resource does not exist");
    }
}
