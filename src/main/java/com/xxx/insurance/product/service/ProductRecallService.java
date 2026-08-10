package com.xxx.insurance.product.service;

import com.xxx.insurance.product.model.ProductRecallExecutionContext;
import com.xxx.insurance.product.model.ProductRecallRequest;
import com.xxx.insurance.product.model.ProductRecallResult;

/**
 * 产品召回服务边界。
 *
 * <p>当前实现返回 Mock 候选；未来接入行内向量召回微服务时，只替换该接口实现，
 * Graph 节点、审计表和 REST 合同保持不变。</p>
 */
public interface ProductRecallService {

    ProductRecallResult recall(ProductRecallRequest request, ProductRecallExecutionContext context);
}
