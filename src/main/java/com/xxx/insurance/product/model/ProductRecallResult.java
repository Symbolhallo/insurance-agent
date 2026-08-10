package com.xxx.insurance.product.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * 产品召回结果。
 *
 * @param retrievalCallId 召回调用编号
 * @param query 实际召回文本
 * @param topK 最大候选数量
 * @param candidates 候选产品
 * @param mockData 是否为 Mock 数据
 * @param durationMs 召回耗时
 * @param recalledAt 召回时间
 */
@Schema(description = "产品召回结果")
public record ProductRecallResult(
        @Schema(description = "召回调用编号")
        String retrievalCallId,

        @Schema(description = "实际召回文本")
        String query,

        @Schema(description = "最大候选数量")
        int topK,

        @Schema(description = "候选产品")
        List<ProductCandidate> candidates,

        @Schema(description = "是否为 Mock 数据", example = "true")
        boolean mockData,

        @Schema(description = "召回耗时，单位毫秒")
        long durationMs,

        @Schema(description = "召回时间")
        Instant recalledAt) {
}
