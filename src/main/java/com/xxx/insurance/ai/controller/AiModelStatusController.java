package com.xxx.insurance.ai.controller;

import com.xxx.insurance.ai.model.AiModelStatus;
import com.xxx.insurance.ai.service.AiModelStatusService;
import com.xxx.insurance.common.result.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 模型联调状态 API。
 *
 * <p>该接口只返回脱敏后的运行配置与当前 Agent 装配状态，不调用模型、不泄露 API Key。
 * 本地使用 Swagger UI 做真实 DeepSeek 调用前，可以先通过该接口确认环境变量是否生效。</p>
 */
@Tag(name = "AiModel", description = "AI 模型联调状态接口")
@RestController
@RequestMapping("/api/v1/ai/model")
public class AiModelStatusController {

    private final AiModelStatusService aiModelStatusService;

    public AiModelStatusController(AiModelStatusService aiModelStatusService) {
        this.aiModelStatusService = aiModelStatusService;
    }

    @Operation(
            summary = "查看当前 AI 模型联调状态",
            description = "返回 OpenAI-compatible 模型配置、API Key 是否已配置、当前 ProductAnalysisAgent 的 Skill 和 Tool 装配状态。")
    @GetMapping("/status")
    public ApiResponse<AiModelStatus> status() {
        return ApiResponse.success(aiModelStatusService.currentStatus());
    }
}
