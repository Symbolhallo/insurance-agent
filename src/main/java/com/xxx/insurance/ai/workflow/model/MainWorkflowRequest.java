package com.xxx.insurance.ai.workflow.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 主工作流请求。
 *
 * @param message 用户自然语言问题
 * @param conversationId 会话编号
 */
@Schema(description = "主工作流请求")
public record MainWorkflowRequest(
        @Schema(description = "用户输入的问题", example = "鑫享人生收益怎么样？")
        @NotBlank(message = "message must not be blank")
        @Size(max = 2000, message = "message length must be less than or equal to 2000")
        String message,

        @Schema(description = "会话编号，Workflow、Memory 和审计链路必传", example = "local-test-001")
        @NotBlank(message = "conversationId must not be blank")
        @Size(max = 64, message = "conversationId length must be less than or equal to 64")
        String conversationId) {
}
