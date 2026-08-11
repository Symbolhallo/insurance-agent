package com.xxx.insurance.ai.workflow.model;

import java.time.Instant;

/**
 * 工作流恢复所需的实例视图。
 *
 * @param workflowInstanceId 工作流实例编号
 * @param conversationId 会话编号
 * @param status 当前执行状态
 * @param executionOwner 当前执行租约持有者
 * @param leaseUntil 当前执行租约到期时间
 * @param executionFenceToken 当前执行权代次
 * @param createdAt 首次启动时间
 */
public record WorkflowInstanceExecutionView(
        String workflowInstanceId,
        String conversationId,
        String status,
        String executionOwner,
        Instant leaseUntil,
        long executionFenceToken,
        Instant createdAt) {

    /** 兼容只关注会话和状态的读取场景。 */
    public WorkflowInstanceExecutionView(String workflowInstanceId,
                                         String conversationId,
                                         String status,
                                         Instant createdAt) {
        this(workflowInstanceId, conversationId, status, null, null, 0L, createdAt);
    }
}
