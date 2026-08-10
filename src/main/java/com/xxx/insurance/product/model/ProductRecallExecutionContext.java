package com.xxx.insurance.product.model;

/**
 * 产品召回执行上下文。
 *
 * @param conversationId 会话编号
 * @param workflowInstanceId 工作流实例编号，独立 API 调用时为空
 */
public record ProductRecallExecutionContext(
        String conversationId,
        String workflowInstanceId) {
}
