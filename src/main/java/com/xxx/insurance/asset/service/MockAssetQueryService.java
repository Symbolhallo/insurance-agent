package com.xxx.insurance.asset.service;

import com.xxx.insurance.asset.model.AssetPosition;
import com.xxx.insurance.asset.model.AssetQueryResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 本地资产 Mock 数据实现，不连接真实账户或核心系统。 */
@Service
public class MockAssetQueryService implements AssetQueryService {

    public static final String MOCK_CUSTOMER_ID = "MOCK-CUSTOMER-001";

    private static final List<AssetPosition> POSITIONS = List.of(
            new AssetPosition(
                    "6222 **** **** 0188", "DEPOSIT", "人民币活期存款", new BigDecimal("186500.25"),
                    "CNY", LocalDate.of(2026, 8, 10), "R1", "随时支取", "MockAssetQueryService"),
            new AssetPosition(
                    "理财账户 **** 0366", "WEALTH_MANAGEMENT", "稳健型固收理财Mock", new BigDecimal("300000.00"),
                    "CNY", LocalDate.of(2026, 8, 10), "R2", "预计30日内到期", "MockAssetQueryService"),
            new AssetPosition(
                    "基金账户 **** 1024", "FUND", "宽基指数基金Mock", new BigDecimal("125800.50"),
                    "CNY", LocalDate.of(2026, 8, 10), "R4", "交易日可赎回，净值波动", "MockAssetQueryService"));

    /** 只允许查询固定 Mock 客户，并按资产类型做确定性筛选。 */
    @Override
    public AssetQueryResult queryAssets(String customerId, String assetType) {
        String effectiveCustomerId = StringUtils.hasText(customerId)
                ? customerId.trim() : MOCK_CUSTOMER_ID;
        if (!MOCK_CUSTOMER_ID.equals(effectiveCustomerId)) {
            throw new IllegalArgumentException("Only MOCK-CUSTOMER-001 is available in current phase");
        }
        List<AssetPosition> matches = POSITIONS.stream()
                .filter(position -> !StringUtils.hasText(assetType)
                        || position.assetType().equalsIgnoreCase(assetType.trim()))
                .toList();
        BigDecimal total = matches.stream()
                .map(AssetPosition::marketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new AssetQueryResult(
                effectiveCustomerId, total, "CNY", matches, true, Instant.now());
    }
}
