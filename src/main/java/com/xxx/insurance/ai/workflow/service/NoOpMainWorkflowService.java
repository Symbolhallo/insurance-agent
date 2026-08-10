package com.xxx.insurance.ai.workflow.service;

import com.xxx.insurance.ai.workflow.model.MainWorkflowRequest;
import com.xxx.insurance.ai.workflow.model.MainWorkflowResponse;
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

    @Override
    public MainWorkflowResponse run(MainWorkflowRequest request) {
        Instant now = Instant.now();
        return new MainWorkflowResponse(
                false,
                WORKFLOW_CODE,
                null,
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
}
