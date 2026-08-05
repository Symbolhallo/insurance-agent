package com.xxx.insurance.product.model;

import java.util.List;

/**
 * 产品分析受控输出结果。
 *
 * @param summary 结果摘要
 * @param productItems 产品条目
 * @param missingProductCodes 未找到的产品编码
 * @param complianceNotes 合规提示
 */
public record ProductAnalysisResult(
        String summary,
        List<ProductAnalysisItem> productItems,
        List<String> missingProductCodes,
        List<String> complianceNotes) {

    public record ProductAnalysisItem(
            String productCode,
            String productName,
            String productType,
            String insurerName,
            List<String> highlights,
            List<String> limitations,
            String targetCustomer) {
    }
}
