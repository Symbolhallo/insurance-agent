package com.xxx.insurance.ai.workflow.sse.model;

import java.time.Instant;

/**
 * ai_workflow_sse_event 的 MyBatis 持久化记录。
 *
 * @param eventId 事件编号
 * @param workflowInstanceId 工作流实例编号
 * @param conversationId 会话编号
 * @param sequenceNo 工作流内事件序号
 * @param eventType 事件类型
 * @param nodeCode 节点编码
 * @param payloadJson 脱敏后的事件数据 JSON
 * @param createdAt 创建时间
 * @param expireAt 重放过期时间
 */
public record WorkflowSseEventRecord(
        String eventId,
        String workflowInstanceId,
        String conversationId,
        long sequenceNo,
        String eventType,
        String nodeCode,
        String payloadJson,
        Instant createdAt,
        Instant expireAt) {
}
