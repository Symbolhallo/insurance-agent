package com.xxx.insurance.ai.workflow.checkpoint.model;

import java.time.Instant;

/**
 * Graph Checkpoint 线程持久化记录。
 *
 * @param threadId Spring AI Alibaba Graph 线程编号
 * @param workflowInstanceId 关联的工作流实例编号
 * @param conversationId 关联的会话编号
 * @param status Checkpoint 线程状态
 * @param latestCheckpointId 当前线程最新 Checkpoint 编号
 * @param version 线程记录乐观锁版本号
 * @param expiresAt Checkpoint 数据过期时间
 * @param releasedAt Graph 线程释放时间
 * @param createdAt 线程记录创建时间
 * @param updatedAt 线程记录最后更新时间
 */
public record GraphCheckpointThreadRecord(
        String threadId,
        String workflowInstanceId,
        String conversationId,
        String status,
        String latestCheckpointId,
        long version,
        Instant expiresAt,
        Instant releasedAt,
        Instant createdAt,
        Instant updatedAt) {
}
