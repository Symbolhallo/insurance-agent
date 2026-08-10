package com.xxx.insurance.asset.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 客户资产查询 Tool 返回结果。
 *
 * @param customerId Mock 客户编号
 * @param totalMarketValue 总资产市值
 * @param currency 汇总币种
 * @param positions 持仓列表
 * @param mockData 是否为 Mock 数据
 * @param queriedAt 查询时间
 */
@Schema(description = "客户资产查询 Mock 结果")
public record AssetQueryResult(
        String customerId,
        BigDecimal totalMarketValue,
        String currency,
        List<AssetPosition> positions,
        boolean mockData,
        Instant queriedAt) {

    public AssetQueryResult {
        positions = positions == null ? List.of() : List.copyOf(positions);
    }
}
