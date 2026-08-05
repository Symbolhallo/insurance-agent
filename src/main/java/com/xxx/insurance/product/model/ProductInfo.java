package com.xxx.insurance.product.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Mock产品基础信息。
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
