package com.xxx.insurance.policy.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * 客户保单查询 Tool 返回结果。
 *
 * @param customerId Mock 客户编号
 * @param policies 命中的保单列表
 * @param totalCount 命中数量
 * @param mockData 是否为 Mock 数据
 * @param queriedAt 查询时间
 */
@Schema(description = "客户保单查询 Mock 结果")
public record PolicyQueryResult(
        String customerId,
        List<PolicyInfo> policies,
        int totalCount,
        boolean mockData,
        Instant queriedAt) {

    public PolicyQueryResult {
        policies = policies == null ? List.of() : List.copyOf(policies);
    }
}
