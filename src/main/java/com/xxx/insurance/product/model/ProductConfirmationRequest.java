package com.xxx.insurance.product.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 产品候选人工确认请求。
 *
 * @param conversationId 会话编号
 * @param selectedProductCodes 用户选中的候选产品编码
 */
@Schema(description = "产品候选人工确认请求")
public record ProductConfirmationRequest(
        @Schema(description = "会话编号")
        @NotBlank(message = "conversationId must not be blank")
        @Size(max = 64, message = "conversationId length must be less than or equal to 64")
        String conversationId,

        @Schema(description = "用户选中的候选产品编码")
        @NotEmpty(message = "selectedProductCodes must not be empty")
        @Size(max = 10, message = "selectedProductCodes size must be less than or equal to 10")
        List<@NotBlank(message = "selectedProductCodes must not contain blank values") String>
                selectedProductCodes) {
}
