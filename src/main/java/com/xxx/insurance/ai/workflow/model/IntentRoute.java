package com.xxx.insurance.ai.workflow.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 单个业务意图到子智能体的受控路由。
 *
 * @param intent 标准意图编码
 * @param targetAgent 白名单目标智能体
 * @param intentionQuery 交给该意图处理的独立问题
 * @param reason 路由理由
 */
@Schema(description = "单个业务意图路由")
public record IntentRoute(
        @Schema(description = "标准意图编码", example = "KNOWLEDGE_QA")
        String intent,

        @Schema(description = "白名单目标智能体", example = "knowledge-qa-agent")
        String targetAgent,

        @Schema(description = "该意图对应的独立问题")
        String intentionQuery,

        @Schema(description = "路由理由")
        String reason) {
}
