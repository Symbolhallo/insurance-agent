package com.xxx.insurance.ai.workflow.service;

import com.xxx.insurance.ai.workflow.model.MainWorkflowRequest;
import com.xxx.insurance.ai.workflow.model.MainWorkflowResponse;
import com.xxx.insurance.ai.workflow.model.WorkflowResumeRequest;
import com.xxx.insurance.product.model.ProductConfirmationRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * 默认主工作流服务。
 */
@Service
@Profile("!local-db")
public class NoOpMainWorkflowService implements MainWorkflowService {

    /** 默认 profile 仍生成格式一致的实例编号，便于 API 合同保持稳定。 */
    @Override
    public String createWorkflowInstanceId() {
        return "wfi-" + java.util.UUID.randomUUID().toString().replace("-", "");
    }

    /** 默认 profile 使用临时实例编号返回禁用响应。 */
    @Override
    public MainWorkflowResponse run(MainWorkflowRequest request) {
        return run(createWorkflowInstanceId(), request);
    }

    /** 默认 profile 不执行 Graph，仅返回持久化未启用说明。 */
    @Override
    public MainWorkflowResponse run(String workflowInstanceId, MainWorkflowRequest request) {
        return run(workflowInstanceId, request, false);
    }

    /** 默认 profile 忽略 Token 流开关并返回统一禁用响应。 */
    @Override
    public MainWorkflowResponse run(String workflowInstanceId,
                                    MainWorkflowRequest request,
                                    boolean tokenStreamingEnabled) {
        Instant now = Instant.now();
        return new MainWorkflowResponse(
                false,
                WORKFLOW_CODE,
                workflowInstanceId,
                null,
                Map.of(),
                request.conversationId(),
                request.message(),
                null,
                null,
                Map.of(),
                null,
                null,
                null,
                false,
                java.util.List.of(),
                null,
                "DISABLED",
                null,
                null,
                null,
                null,
                null,
                0,
                now,
                now,
                "Workflow persistence is disabled. Start application with local-db profile.");
    }

    @Override
    public MainWorkflowResponse confirmProducts(String workflowInstanceId,
                                                ProductConfirmationRequest request) {
        return run(new MainWorkflowRequest("product confirmation", request.conversationId()));
    }

    /** 默认 profile 不启用持久化恢复，返回统一禁用响应。 */
    @Override
    public MainWorkflowResponse resume(String workflowInstanceId, WorkflowResumeRequest request) {
        return run(workflowInstanceId,
                new MainWorkflowRequest("workflow resume", request.conversationId()));
    }
}
