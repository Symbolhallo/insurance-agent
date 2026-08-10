package com.xxx.insurance.ai.workflow.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 产品线索解析模型的结构化输出。
 *
 * @param detectedProductClues 当前输入中识别到的产品名称、代码、简称或指代
 * @param matchedConfirmedProductCodes 可唯一映射到会话已确认产品的编码
 * @param productRecallDecision 是否需要进入候选召回和人工确认
 */
@Schema(description = "产品线索解析模型输出")
public record ProductReferenceResolutionModelOutput(
        @Schema(description = "当前输入中的产品线索")
        List<String> detectedProductClues,

        @Schema(description = "唯一映射到会话已确认产品的编码")
        List<String> matchedConfirmedProductCodes,

        @Schema(description = "候选召回判断")
        ProductRecallDecision productRecallDecision) {
}
