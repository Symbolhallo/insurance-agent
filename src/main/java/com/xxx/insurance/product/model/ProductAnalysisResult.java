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

    /**
     * 单个保险产品的格式化分析结果。
     *
     * @param productCode 产品编码
     * @param productName 产品名称
     * @param productType 产品类型
     * @param insurerName 保险公司名称
     * @param highlights 产品保障亮点
     * @param limitations 产品限制与风险提示
     * @param targetCustomer 适用客户描述
     */
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
