package com.xxx.insurance.product.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * 产品召回候选。
 *
 * @param productCode 产品编码
 * @param productName 产品名称
 * @param productType 产品类型
 * @param insurerName 保险公司名称
 * @param score Mock 相关性分数
 * @param matchReason 命中原因
 */
@Schema(description = "产品召回候选")
public record ProductCandidate(
        @Schema(description = "产品编码", example = "PA-002")
        String productCode,

        @Schema(description = "产品名称", example = "康健无忧重大疾病保险")
        String productName,

        @Schema(description = "产品类型", example = "重大疾病保险")
        String productType,

        @Schema(description = "保险公司名称", example = "示例健康保险股份有限公司")
        String insurerName,

        @Schema(description = "Mock 相关性分数", example = "0.98")
        BigDecimal score,

        @Schema(description = "候选命中原因", example = "查询命中产品类型：重大疾病保险")
        String matchReason) {
}
