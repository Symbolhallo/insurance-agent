package com.xxx.insurance.ai.workflow.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 主工作流请求。
 *
 * @param message 用户自然语言问题
 * @param conversationId 会话编号
 * @param requestId 调用方生成的请求幂等编号；同一会话内不得重复
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
        String conversationId,

        @Schema(description = "请求幂等编号，同一 conversationId 内唯一", example = "req-20260810-0001")
        @NotBlank(message = "requestId must not be blank")
        @Size(max = 64, message = "requestId length must be less than or equal to 64")
        String requestId) {

    /** 仅供内部调用和单元测试生成独立请求；外部 API 必须显式传入 requestId。 */
    public MainWorkflowRequest(String message, String conversationId) {
        this(message, conversationId, "req-" + UUID.randomUUID().toString().replace("-", ""));
    }
}
