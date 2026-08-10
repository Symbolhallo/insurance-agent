package com.xxx.insurance.ai.workflow.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 意图路由结果。
 *
 * @param intent 识别出的标准业务意图编码
 * @param targetAgent 承接该业务意图的目标智能体名称
 * @param reason 路由到目标智能体的简要理由，不包含模型内部思维过程
 * @param routes 当前请求包含的全部受控意图路由
 */
@Schema(description = "工作流意图路由结果")
public record IntentRoutingResult(
        @Schema(description = "识别出的标准业务意图编码", example = "PRODUCT_ANALYSIS")
        String intent,

        @Schema(description = "承接该业务意图的目标智能体名称", example = "product-analysis-agent")
        String targetAgent,

        @Schema(description = "路由到目标智能体的简要理由，不包含模型内部思维过程")
        String reason,

        @Schema(description = "当前请求包含的全部受控意图路由")
        java.util.List<IntentRoute> routes) {

    public IntentRoutingResult(String intent, String targetAgent, String reason) {
        this(intent, targetAgent, reason, java.util.List.of(
                new IntentRoute(intent, targetAgent, null, reason)));
    }
}
