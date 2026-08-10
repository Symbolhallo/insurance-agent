package com.xxx.insurance.ai.workflow.checkpoint.model;

import java.time.Instant;

/**
 * Graph Checkpoint 状态持久化记录。
 *
 * @param checkpointId Checkpoint 编号
 * @param threadId Spring AI Alibaba Graph 线程编号
 * @param parentCheckpointId 父 Checkpoint 编号，首个版本为空
 * @param checkpointVersion 当前线程内的 Checkpoint 版本号
 * @param nodeId 生成该 Checkpoint 的节点编号
 * @param nextNodeId 恢复执行时的下一个节点编号
 * @param statePayload 序列化后的 Graph State 二进制内容
 * @param stateContentType State 序列化内容类型
 * @param stateSchemaVersion State 数据结构版本号
 * @param createdAt Checkpoint 创建时间
 */
public record GraphCheckpointRecord(
        String checkpointId,
        String threadId,
        String parentCheckpointId,
        long checkpointVersion,
        String nodeId,
        String nextNodeId,
        byte[] statePayload,
        String stateContentType,
        int stateSchemaVersion,
        Instant createdAt) {
}
