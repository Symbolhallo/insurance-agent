package com.xxx.insurance.ai.workflow.model;

import com.xxx.insurance.product.model.ConfirmedProduct;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 产品实体解析节点输出。
 *
 * @param conversationId 会话编号
 * @param currentQuery 当前用户原始输入
 * @param conversationConfirmedProducts 当前会话已经确认的全部产品
 * @param detectedProductClues 当前输入识别出的具体产品线索
 * @param productRecallDecision 候选召回判断
 * @param resolvedProducts 无需人工确认时已唯一解析出的标准产品
 */
@Schema(description = "产品实体解析结果")
public record ProductReferenceResolution(
        @Schema(description = "会话编号")
        String conversationId,

        @Schema(description = "当前用户原始输入")
        String currentQuery,

        @Schema(description = "当前会话已经确认的全部产品")
        List<ConfirmedProduct> conversationConfirmedProducts,

        @Schema(description = "当前输入识别出的具体产品线索")
        List<String> detectedProductClues,

        @Schema(description = "候选召回判断")
        ProductRecallDecision productRecallDecision,

        @Schema(description = "已唯一解析出的标准产品")
        List<ConfirmedProduct> resolvedProducts) {
}
