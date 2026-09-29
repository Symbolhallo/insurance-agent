package com.xxx.insurance.common.security;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** 无数据库 profile 不存在可查询的持久化资源，保留调用链兼容。 */
@Service
@Profile("!local-db")
public class NoOpResourceAccessService implements ResourceAccessService {

    @Override
    public void claimConversation(RequestIdentity identity, String conversationId) {
    }

    @Override
    public void requireConversationAccess(RequestIdentity identity, String conversationId) {
    }

    @Override
    public void requireWorkflowAccess(RequestIdentity identity, String workflowInstanceId) {
    }
}
