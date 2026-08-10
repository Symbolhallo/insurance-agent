package com.xxx.insurance.asset.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Mock 客户资产持仓。
 *
 * @param accountNumberMasked 脱敏账号
 * @param assetType 资产类型
 * @param assetName 资产名称
 * @param marketValue 当前市值或余额
 * @param currency 币种
 * @param valuationDate 估值日期
 * @param riskLevel 风险等级
 * @param liquidity 流动性说明
 * @param source 数据来源
 */
@Schema(description = "Mock 客户资产持仓")
public record AssetPosition(
        String accountNumberMasked,
        String assetType,
        String assetName,
        BigDecimal marketValue,
        String currency,
        LocalDate valuationDate,
        String riskLevel,
        String liquidity,
        String source) {
}
