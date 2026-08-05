package com.xxx.insurance.product.model;

import java.util.List;

/**
 * 产品分析原始业务数据。
 *
 * <p>该对象承载 Service 返回的确定性业务数据，不包含大模型推理结论。
 * 后续 ProductAnalysisTool 可直接复用该结构作为工具执行结果。</p>
 */
public record ProductAnalysisData(
        ProductAnalysisRequest request,
        List<ProductInfo> products,
        List<String> missingProductCodes) {
}
