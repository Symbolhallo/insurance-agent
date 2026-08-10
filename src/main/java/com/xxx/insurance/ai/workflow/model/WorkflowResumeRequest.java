package com.xxx.insurance.ai.workflow.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 主工作流主动恢复请求。
 *
 * @param conversationId 会话编号，必须与原工作流实例一致
 */
@Schema(description = "主工作流主动恢复请求")
public record WorkflowResumeRequest(
        @Schema(description = "原工作流所属会话编号", example = "dynamic-dag-test-001")
        @NotBlank(message = "conversationId must not be blank")
        @Size(max = 64, message = "conversationId length must be less than or equal to 64")
        String conversationId) {
}
