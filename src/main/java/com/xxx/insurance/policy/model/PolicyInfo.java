package com.xxx.insurance.policy.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Mock 客户保单明细。
 *
 * @param policyNumberMasked 脱敏保单号
 * @param productCode 产品编码
 * @param productName 产品名称
 * @param policyStatus 保单状态
 * @param insuredNameMasked 被保险人脱敏姓名
 * @param sumInsured 基本保额
 * @param annualPremium 年交保费
 * @param effectiveDate 生效日期
 * @param paymentStatus 缴费状态
 * @param nextPaymentDate 下次缴费日期
 * @param currency 币种
 * @param source 数据来源
 */
@Schema(description = "Mock 客户保单明细")
public record PolicyInfo(
        String policyNumberMasked,
        String productCode,
        String productName,
        String policyStatus,
        String insuredNameMasked,
        BigDecimal sumInsured,
        BigDecimal annualPremium,
        LocalDate effectiveDate,
        String paymentStatus,
        LocalDate nextPaymentDate,
        String currency,
        String source) {
}
