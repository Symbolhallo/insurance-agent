package com.xxx.insurance.common.security;

/** 会话和工作流根资源的租户/用户所有权边界。 */
public interface ResourceAccessService {

    /** 幂等认领新会话；已被其他租户或用户认领时按不存在处理。 */
    void claimConversation(RequestIdentity identity, String conversationId);

    /** 校验当前身份拥有指定会话。 */
    void requireConversationAccess(RequestIdentity identity, String conversationId);

    /** 校验当前身份拥有指定工作流实例。 */
    void requireWorkflowAccess(RequestIdentity identity, String workflowInstanceId);
}
