package com.xxx.insurance.knowledge.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 保险业务知识问答响应。
 *
 * @param agentName 智能体名称
 * @param conversationId 会话编号
 * @param invocationId 调用编号
 * @param answer 模型回答
 * @param modelInvoked 是否调用模型
 * @param durationMs 调用耗时
 * @param answeredAt 回答时间
 * @param answerLength 回答字符数
 * @param memoryEnabled 是否启用记忆
 * @param memoryMessageCount 携带的历史消息数
 */
@Schema(description = "保险业务知识问答响应")
public record KnowledgeQaChatResponse(
        @Schema(description = "智能体名称", example = "knowledge-qa-agent")
        String agentName,

        @Schema(description = "会话编号")
        String conversationId,

        @Schema(description = "调用编号")
        String invocationId,

        @Schema(description = "模型回答")
        String answer,

        @Schema(description = "是否调用模型", example = "true")
        boolean modelInvoked,

        @Schema(description = "调用耗时，单位毫秒")
        long durationMs,

        @Schema(description = "回答时间")
        Instant answeredAt,

        @Schema(description = "回答字符数")
        int answerLength,

        @Schema(description = "是否启用记忆")
        boolean memoryEnabled,

        @Schema(description = "携带的历史消息数")
        int memoryMessageCount) {
}
