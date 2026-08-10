package com.xxx.insurance.product.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 当前会话中由用户确认的标准产品信息。
 *
 * @param conversationId 会话编号
 * @param productCode 标准产品编码
 * @param productName 标准产品名称
 * @param productType 产品类型
 * @param insurerName 保险公司名称
 * @param sourceClue 触发候选召回的原始产品线索
 * @param retrievalCallId 关联召回调用编号
 * @param workflowInstanceId 关联工作流实例编号
 * @param confirmedAt 用户确认时间
 */
@Schema(description = "会话内已确认的标准产品")
public record ConfirmedProduct(
        @Schema(description = "会话编号")
        String conversationId,

        @Schema(description = "标准产品编码")
        String productCode,

        @Schema(description = "标准产品名称")
        String productName,

        @Schema(description = "产品类型")
        String productType,

        @Schema(description = "保险公司名称")
        String insurerName,

        @Schema(description = "触发候选召回的原始产品线索")
        String sourceClue,

        @Schema(description = "关联召回调用编号")
        String retrievalCallId,

        @Schema(description = "关联工作流实例编号")
        String workflowInstanceId,

        @Schema(description = "用户确认时间")
        Instant confirmedAt) {
}
