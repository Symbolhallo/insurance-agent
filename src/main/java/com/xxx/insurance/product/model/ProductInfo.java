package com.xxx.insurance.product.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Mock产品基础信息。
 *
 * @param productCode 产品编码
 * @param productName 产品名称
 * @param productType 产品类型
 * @param insurerName 保险公司名称
 * @param coverageResponsibilities 保障责任列表
 * @param targetCustomer 适用客户描述
 * @param paymentPeriod 缴费期间
 * @param minimumPremium 最低保费
 * @param riskNotes 风险提示列表
 */
public record ProductInfo(
        String productCode,
        String productName,
        String productType,
        String insurerName,
        List<String> coverageResponsibilities,
        String targetCustomer,
        String paymentPeriod,
        BigDecimal minimumPremium,
        List<String> riskNotes) {
}
