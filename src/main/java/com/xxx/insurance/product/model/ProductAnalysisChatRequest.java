package com.xxx.insurance.product.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 产品分析智能体自然语言调用请求。
 *
 * @param message 用户自然语言问题
 * @param conversationId 会话标识，当前仅透传，后续接入 Memory 时使用
 */
@Schema(description = "产品分析智能体自然语言调用请求")
public record ProductAnalysisChatRequest(
        @Schema(description = "用户输入的产品分析问题", example = "请分析 PA-001 是否适合长期保障规划")
        @NotBlank(message = "message must not be blank")
        @Size(max = 2000, message = "message length must be less than or equal to 2000")
        String message,

        @Schema(description = "会话编号，当前阶段仅透传，后续用于 Memory 和审计链路", example = "local-test-001")
        @Size(max = 128, message = "conversationId length must be less than or equal to 128")
        String conversationId) {
}
