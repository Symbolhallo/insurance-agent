package com.xxx.insurance.ai.workflow.model;

import java.time.Instant;

/**
 * 工作流恢复所需的实例视图。
 *
 * @param workflowInstanceId 工作流实例编号
 * @param conversationId 会话编号
 * @param status 当前执行状态
 * @param createdAt 首次启动时间
 */
public record WorkflowInstanceExecutionView(
        String workflowInstanceId,
        String conversationId,
        String status,
        Instant createdAt) {
}
