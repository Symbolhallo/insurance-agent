package com.xxx.insurance.product.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 产品分析智能体自然语言调用响应。
 *
 * @param agentName 智能体名称
 * @param conversationId 会话标识
 * @param answer 模型回答
 * @param modelInvoked 是否触发模型调用
 */
@Schema(description = "产品分析智能体自然语言调用响应")
public record ProductAnalysisChatResponse(
        @Schema(description = "智能体名称", example = "product-analysis-agent")
        String agentName,

        @Schema(description = "会话编号，当前阶段仅透传", example = "local-test-001")
        String conversationId,

        @Schema(description = "模型生成的回答")
        String answer,

        @Schema(description = "是否实际触发模型调用", example = "true")
        boolean modelInvoked) {
}
