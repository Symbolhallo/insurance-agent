package com.xxx.insurance.product.model;

import java.util.List;

/**
 * 产品分析请求。
 *
 * @param productCodes 产品编码列表
 * @param customerProfile 客户画像或需求描述
 * @param analysisDimensions 分析维度
 */
public record ProductAnalysisRequest(
        List<String> productCodes,
        String customerProfile,
        List<String> analysisDimensions) {
}
