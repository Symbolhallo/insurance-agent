package com.xxx.insurance.ai.retrieval.model;

import java.time.Instant;

/**
 * 外部召回调用审计记录。
 *
 * @param retrievalCallId 召回调用编号
 * @param conversationId 会话编号
 * @param invocationId Agent 调用编号
 * @param workflowInstanceId 工作流实例编号
 * @param domain 召回领域
 * @param queryText 查询文本
 * @param topK 最大召回数量
 * @param filtersJson 过滤条件 JSON
 * @param resultJson 召回结果 JSON
 * @param durationMs 召回耗时
 * @param status 调用状态
 * @param errorMessage 错误信息
 * @param createdAt 创建时间
 */
public record RetrievalCallRecord(
        String retrievalCallId,
        String conversationId,
        String invocationId,
        String workflowInstanceId,
        String domain,
        String queryText,
        int topK,
        String filtersJson,
        String resultJson,
        Long durationMs,
        String status,
        String errorMessage,
        Instant createdAt) {
}
