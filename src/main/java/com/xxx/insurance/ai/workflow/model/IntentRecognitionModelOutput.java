package com.xxx.insurance.ai.workflow.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 意图识别模型结构化输出。
 *
 * @param intentions 识别并拆分出的业务意图，当前最多两个
 * @param reason 整体分类理由，不包含模型内部思维过程
 */
@Schema(description = "意图识别模型输出")
public record IntentRecognitionModelOutput(
        @Schema(description = "识别并拆分出的业务意图")
        java.util.List<RecognizedIntent> intentions,

        @Schema(description = "分类理由，不包含模型内部思维过程")
        String reason) {
}
