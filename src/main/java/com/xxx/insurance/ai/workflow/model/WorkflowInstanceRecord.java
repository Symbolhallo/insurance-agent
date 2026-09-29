package com.xxx.insurance.ai.workflow.model;

import java.time.Instant;

/**
 * Workflow 实例持久化记录。
 *
 * @param workflowInstanceId 工作流实例编号
 * @param workflowCode 工作流定义编码
 * @param conversationId 关联的会话编号
 * @param tenantId 所属租户编号
 * @param userId 所属用户编号
 * @param customerId 关联客户编号
 * @param operatorId 操作员编号
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
        String tenantId,
        String userId,
        String customerId,
        String operatorId,
        String requestId,
        String traceId,
        String status,
        String inputJson,
        String executionOwner,
        Instant leaseUntil,
        Instant createdAt) {

    /** 兼容未显式关注租户身份的底层事务测试。 */
    public WorkflowInstanceRecord(String workflowInstanceId,
                                  String workflowCode,
                                  String conversationId,
                                  String requestId,
                                  String traceId,
                                  String status,
                                  String inputJson,
                                  String executionOwner,
                                  Instant leaseUntil,
                                  Instant createdAt) {
        this(workflowInstanceId, workflowCode, conversationId,
                "local-tenant", "mock-user", "MOCK-CUSTOMER-001", "mock-operator",
                requestId, traceId, status, inputJson, executionOwner, leaseUntil, createdAt);
    }
}
