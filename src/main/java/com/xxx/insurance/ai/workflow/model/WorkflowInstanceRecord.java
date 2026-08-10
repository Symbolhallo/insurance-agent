package com.xxx.insurance.ai.workflow.model;

import java.time.Instant;

/**
 * Workflow 实例持久化记录。
 *
 * @param workflowInstanceId 工作流实例编号
 * @param workflowCode 工作流定义编码
 * @param conversationId 关联的会话编号
 * @param requestId 同一会话内唯一的请求幂等编号
 * @param traceId 关联的链路追踪编号
 * @param status 工作流实例状态
 * @param inputJson 工作流原始输入 JSON
 * @param executionOwner 当前持有执行租约的应用实例
 * @param leaseUntil 执行租约截止时间
 * @param createdAt 工作流实例创建时间
 */
public record WorkflowInstanceRecord(
        String workflowInstanceId,
        String workflowCode,
        String conversationId,
        String requestId,
        String traceId,
        String status,
        String inputJson,
        String executionOwner,
        Instant leaseUntil,
        Instant createdAt) {
}
