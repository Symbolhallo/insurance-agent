package com.xxx.insurance.ai.workflow.service;

import com.xxx.insurance.ai.workflow.model.MainWorkflowRequest;
import com.xxx.insurance.ai.workflow.model.MainWorkflowResponse;
import com.xxx.insurance.ai.workflow.model.WorkflowResumeRequest;
import com.xxx.insurance.product.model.ProductConfirmationRequest;

/**
 * 主工作流服务。
 */
public interface MainWorkflowService {

    String WORKFLOW_CODE = "main-workflow-v1";

    /** 生成尚未执行的工作流实例编号，供同步和 SSE 入口统一使用。 */
    String createWorkflowInstanceId();

    /** 生成实例编号并同步执行主工作流。 */
    MainWorkflowResponse run(MainWorkflowRequest request);

    /** 使用调用方预先生成的实例编号执行主工作流，供 SSE 在执行前建立订阅。 */
    MainWorkflowResponse run(String workflowInstanceId, MainWorkflowRequest request);

    /** 使用预分配实例编号执行工作流，并显式控制内部 ReactAgent 是否使用 stream API。 */
    MainWorkflowResponse run(String workflowInstanceId,
                             MainWorkflowRequest request,
                             boolean tokenStreamingEnabled);

    /** 确认候选产品并恢复中断的主工作流。 */
    MainWorkflowResponse confirmProducts(String workflowInstanceId, ProductConfirmationRequest request);

    /** 确认候选产品并恢复中断工作流，同时显式控制恢复后模型节点是否流式执行。 */
    MainWorkflowResponse confirmProducts(String workflowInstanceId,
                                         ProductConfirmationRequest request,
                                         boolean tokenStreamingEnabled);

    /** 原子抢占等待确认实例；SSE 入口在建立响应前调用，冲突请求直接返回 409。 */
    long claimProductConfirmation(String workflowInstanceId, String conversationId);

    /** 恢复已经由当前入口抢占为 CONFIRMING 的产品确认工作流。 */
    MainWorkflowResponse confirmClaimedProducts(String workflowInstanceId,
                                                ProductConfirmationRequest request,
                                                boolean tokenStreamingEnabled,
                                                long executionFenceToken);

    /** SSE 后台任务未能提交时释放尚未消费的确认抢占。 */
    void releaseProductConfirmationClaim(String workflowInstanceId,
                                         String conversationId,
                                         long executionFenceToken);

    /** 从最新持久化 Checkpoint 主动恢复异常中断但仍处于 RUNNING 的工作流。 */
    MainWorkflowResponse resume(String workflowInstanceId, WorkflowResumeRequest request);
}
