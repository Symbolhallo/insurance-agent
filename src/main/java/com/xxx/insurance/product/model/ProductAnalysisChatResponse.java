package com.xxx.insurance.product.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * 产品分析智能体自然语言调用响应。
 *
 * @param agentName 智能体名称
 * @param conversationId 会话标识
 * @param invocationId 单次 Agent 调用标识
 * @param answer 模型回答
 * @param modelInvoked 是否触发模型调用
 * @param durationMs 模型调用耗时，单位毫秒
 * @param answeredAt 模型回答生成完成时间
 * @param answerLength 模型回答字符长度
 * @param memoryEnabled 本次调用是否启用 ChatMemory
 * @param memoryMessageCount 调用模型时携带的历史消息数量
 * @param outputFormatValid 是否满足当前 Skill 输出格式合同
 * @param missingSections 缺失的小标题
 */
@Schema(description = "产品分析智能体自然语言调用响应")
public record ProductAnalysisChatResponse(
        @Schema(description = "智能体名称", example = "product-analysis-agent")
        String agentName,

        @Schema(description = "会话编号，当前阶段仅透传", example = "local-test-001")
        String conversationId,

        @Schema(description = "单次 Agent 调用标识", example = "pai-7b65d4eecdd44d73a4d15de78d986f21")
        String invocationId,

        @Schema(description = "模型生成的回答")
        String answer,

        @Schema(description = "是否实际触发模型调用", example = "true")
        boolean modelInvoked,

        @Schema(description = "模型调用耗时，单位毫秒", example = "1280")
        long durationMs,

        @Schema(description = "模型回答生成完成时间")
        Instant answeredAt,

        @Schema(description = "模型回答字符长度", example = "680")
        int answerLength,

        @Schema(description = "本次调用是否启用 ChatMemory", example = "true")
        boolean memoryEnabled,

        @Schema(description = "调用模型时携带的历史消息数量", example = "2")
        int memoryMessageCount,

        @Schema(description = "是否满足当前 Skill 输出格式合同", example = "true")
        boolean outputFormatValid,

        @Schema(description = "缺失的小标题")
        List<String> missingSections) {
}
