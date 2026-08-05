package com.xxx.insurance.product.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 产品分析模型回答格式检查结果。
 *
 * @param outputFormatValid 是否满足当前 Skill 输出格式合同
 * @param missingSections 缺失的小标题
 */
@Schema(description = "产品分析模型回答格式检查结果")
public record ProductAnalysisAnswerInspection(
        @Schema(description = "是否满足当前 Skill 输出格式合同", example = "true")
        boolean outputFormatValid,

        @Schema(description = "缺失的小标题")
        List<String> missingSections) {
}
