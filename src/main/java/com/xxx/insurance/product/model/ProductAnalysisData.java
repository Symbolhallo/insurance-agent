package com.xxx.insurance.product.model;

import java.util.List;

/**
 * 产品分析原始业务数据。
 *
 * <p>该对象承载 Service 返回的确定性业务数据，不包含大模型推理结论。
 * 后续 ProductAnalysisTool 可直接复用该结构作为工具执行结果。</p>
 *
 * @param request 本次产品分析的标准化请求
 * @param products 根据产品编码查询到的产品基础信息
 * @param missingProductCodes 未查询到数据的产品编码
 */
public record ProductAnalysisData(
        ProductAnalysisRequest request,
        List<ProductInfo> products,
        List<String> missingProductCodes) {
}
