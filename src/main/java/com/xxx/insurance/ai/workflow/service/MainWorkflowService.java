package com.xxx.insurance.ai.workflow.service;

import com.xxx.insurance.ai.workflow.model.MainWorkflowRequest;
import com.xxx.insurance.ai.workflow.model.MainWorkflowResponse;
import com.xxx.insurance.product.model.ProductConfirmationRequest;

/**
 * 主工作流服务。
 */
public interface MainWorkflowService {

    String WORKFLOW_CODE = "main-workflow-v1";

    MainWorkflowResponse run(MainWorkflowRequest request);

    MainWorkflowResponse confirmProducts(String workflowInstanceId, ProductConfirmationRequest request);
}
