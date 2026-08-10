package com.xxx.insurance.ai.workflow.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 产品召回判断结果。
 *
 * @param required 是否需要产品召回
 * @param triggerType 召回判断类型
 * @param reason 判断原因
 */
@Schema(description = "产品召回判断结果")
public record ProductRecallDecision(
        @Schema(description = "是否需要产品召回")
        boolean required,

        @Schema(description = "召回判断类型")
        ProductRecallTrigger triggerType,

        @Schema(description = "判断原因")
        String reason) {
}
