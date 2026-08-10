package com.xxx.insurance.knowledge.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 保险业务知识问答请求。
 *
 * @param message 用户问题
 * @param conversationId 会话编号
 */
@Schema(description = "保险业务知识问答请求")
public record KnowledgeQaChatRequest(
        @Schema(description = "保险业务知识问题", example = "保险合同的犹豫期是什么？")
        @NotBlank(message = "message must not be blank")
        @Size(max = 2000, message = "message length must be less than or equal to 2000")
        String message,

        @Schema(description = "会话编号，用于 Memory 和审计链路", example = "knowledge-test-001")
        @Size(max = 64, message = "conversationId length must be less than or equal to 64")
        String conversationId) {
}
