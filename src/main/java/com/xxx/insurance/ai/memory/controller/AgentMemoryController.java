package com.xxx.insurance.ai.memory.controller;

import com.xxx.insurance.ai.memory.model.ConversationMemorySnapshot;
import com.xxx.insurance.ai.memory.model.ConversationSummaryRequest;
import com.xxx.insurance.ai.memory.model.ConversationSummaryResponse;
import com.xxx.insurance.ai.memory.service.AgentMemoryQueryService;
import com.xxx.insurance.ai.memory.service.ConversationSummaryService;
import com.xxx.insurance.common.result.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 记忆观测 API。
 */
@Tag(name = "AgentMemory", description = "Agent 会话记忆与调用流水查询接口")
@Validated
@RestController
@RequestMapping("/api/v1/ai/memory")
public class AgentMemoryController {

    private final AgentMemoryQueryService agentMemoryQueryService;

    private final ConversationSummaryService conversationSummaryService;

    public AgentMemoryController(AgentMemoryQueryService agentMemoryQueryService,
                                 ConversationSummaryService conversationSummaryService) {
        this.agentMemoryQueryService = agentMemoryQueryService;
        this.conversationSummaryService = conversationSummaryService;
    }

    @Operation(
            summary = "查询会话记忆快照",
            description = "按 conversationId 查询会话主记录、窗口记忆、长期记忆和 Agent 调用流水，用于本地 Swagger 验证。")
    @GetMapping("/conversations/{conversationId}")
    public ApiResponse<ConversationMemorySnapshot> getConversationSnapshot(
            @Parameter(description = "会话编号")
            @PathVariable
            @NotBlank(message = "conversationId must not be blank")
            @Size(max = 64, message = "conversationId length must be less than or equal to 64")
            String conversationId,

            @Parameter(description = "长期记忆和调用流水返回条数上限，最大 200")
            @RequestParam(defaultValue = "50")
            @Min(value = 1, message = "limit must be greater than or equal to 1")
            @Max(value = 200, message = "limit must be less than or equal to 200")
            int limit) {
        return ApiResponse.success(agentMemoryQueryService.getConversationSnapshot(conversationId, limit));
    }

    @Operation(
            summary = "调用模型生成会话摘要",
            description = "读取会话长期记忆，调用全局 ChatModel 生成结构化摘要，并保存到 ai_conversation_summary。")
    @PostMapping("/conversations/{conversationId}/summaries")
    public ApiResponse<ConversationSummaryResponse> summarizeConversation(
            @Parameter(description = "会话编号")
            @PathVariable
            @NotBlank(message = "conversationId must not be blank")
            @Size(max = 64, message = "conversationId length must be less than or equal to 64")
            String conversationId,

            @Valid @RequestBody(required = false) ConversationSummaryRequest request) {
        int maxMemories = request == null || request.maxMemories() == null ? 100 : request.maxMemories();
        return ApiResponse.success(conversationSummaryService.summarize(conversationId, maxMemories));
    }
}
