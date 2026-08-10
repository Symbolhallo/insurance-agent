package com.xxx.insurance.ai.workflow.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 上下文对齐阶段识别出的业务实体。
 */
@Schema(description = "工作流识别出的业务实体")
public record WorkflowEntity(
        @Schema(description = "实体类型，例如 PRODUCT", example = "PRODUCT")
        String type,

        @Schema(description = "实体值", example = "鑫享人生")
        String value,

        @Schema(description = "实体来源：CURRENT_QUERY 或 MEMORY", example = "CURRENT_QUERY")
        String source) {
}
