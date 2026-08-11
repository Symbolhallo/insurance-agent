package com.xxx.insurance.ai.workflow.controller;

import com.xxx.insurance.ai.workflow.model.MainWorkflowRequest;
import com.xxx.insurance.ai.workflow.model.MainWorkflowResponse;
import com.xxx.insurance.ai.workflow.model.WorkflowResumeRequest;
import com.xxx.insurance.ai.workflow.service.MainWorkflowService;
import com.xxx.insurance.common.result.ApiResponse;
import com.xxx.insurance.product.model.ProductConfirmationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 主工作流 API。
 */
@Tag(name = "MainWorkflow", description = "主工作流编排接口")
@RestController
@RequestMapping("/api/v1/workflows/main")
public class MainWorkflowController {

    private final MainWorkflowService mainWorkflowService;

    public MainWorkflowController(MainWorkflowService mainWorkflowService) {
        this.mainWorkflowService = mainWorkflowService;
    }

    @Operation(
            summary = "运行 Main Graph v1",
            description = "先解析当前会话产品线索；需要召回时返回候选并等待确认，否则执行上下文对齐、意图识别、子智能体、总结和输出审核节点。")
    @PostMapping("/runs")
    public ApiResponse<MainWorkflowResponse> run(@Valid @RequestBody MainWorkflowRequest request) {
        // 同步兼容入口：复用同一 Main Graph，但不启用 SSE Token 流。
        return ApiResponse.success(mainWorkflowService.run(request));
    }

    @Operation(
            summary = "确认产品候选并恢复 Main Graph",
            description = "校验用户选择属于当前工作流候选，将确认产品限定保存到当前 conversationId，随后从 Checkpoint 恢复执行。")
    @PostMapping("/runs/{workflowInstanceId}/product-confirmations")
    public ApiResponse<MainWorkflowResponse> confirmProducts(
            @PathVariable String workflowInstanceId,
            @Valid @RequestBody ProductConfirmationRequest request) {
        return ApiResponse.success(mainWorkflowService.confirmProducts(workflowInstanceId, request));
    }

    @Operation(
            summary = "从最新 Checkpoint 恢复 Main Graph",
            description = "仅恢复因进程退出等原因仍处于 RUNNING 的实例；WAITING_CONFIRM 必须使用产品确认接口。")
    @PostMapping("/runs/{workflowInstanceId}/resume")
    public ApiResponse<MainWorkflowResponse> resume(
            @PathVariable String workflowInstanceId,
            @Valid @RequestBody WorkflowResumeRequest request) {
        return ApiResponse.success(mainWorkflowService.resume(workflowInstanceId, request));
    }
}
