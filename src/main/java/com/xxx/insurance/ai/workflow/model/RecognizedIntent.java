package com.xxx.insurance.ai.workflow.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 模型识别出的单个业务意图。
 *
 * @param intent 标准意图编码
 * @param intentionQuery 从当前问题拆分出的独立查询
 * @param reason 分类理由
 */
@Schema(description = "模型识别出的业务意图")
public record RecognizedIntent(
        @Schema(description = "标准意图编码")
        String intent,

        @Schema(description = "该意图对应的独立查询")
        String intentionQuery,

        @Schema(description = "分类理由")
        String reason) {
}
