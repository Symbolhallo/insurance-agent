package com.xxx.insurance.policy.service;

import com.xxx.insurance.policy.model.PolicyInfo;
import com.xxx.insurance.policy.model.PolicyQueryResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 本地保单 Mock 数据实现，不连接真实客户系统。 */
@Service
public class MockPolicyQueryService implements PolicyQueryService {

    public static final String MOCK_CUSTOMER_ID = "MOCK-CUSTOMER-001";

    private static final List<PolicyInfo> POLICIES = List.of(
            new PolicyInfo(
                    "P2024****0018", "PA-001", "安享一生终身寿险", "IN_FORCE", "张*",
                    new BigDecimal("500000.00"), new BigDecimal("20000.00"),
                    LocalDate.of(2024, 3, 18), "PAID", LocalDate.of(2027, 3, 18), "CNY",
                    "MockPolicyQueryService"),
            new PolicyInfo(
                    "P2025****0366", "PA-002", "康护无忧重大疾病保险", "IN_FORCE", "张*",
                    new BigDecimal("300000.00"), new BigDecimal("6800.00"),
                    LocalDate.of(2025, 6, 6), "DUE_SOON", LocalDate.of(2026, 9, 6), "CNY",
                    "MockPolicyQueryService"),
            new PolicyInfo(
                    "P2022****1024", "PA-003", "稳盈养老年金保险", "PAID_UP", "李*",
                    new BigDecimal("200000.00"), new BigDecimal("50000.00"),
                    LocalDate.of(2022, 11, 2), "PAYMENT_COMPLETED", null, "CNY",
                    "MockPolicyQueryService"));

    /** 只允许查询固定 Mock 客户，避免测试数据被误认为真实客户数据。 */
    @Override
    public PolicyQueryResult queryPolicies(String customerId, String policyStatus) {
        String effectiveCustomerId = StringUtils.hasText(customerId)
                ? customerId.trim() : MOCK_CUSTOMER_ID;
        if (!MOCK_CUSTOMER_ID.equals(effectiveCustomerId)) {
            throw new IllegalArgumentException("Only MOCK-CUSTOMER-001 is available in current phase");
        }
        List<PolicyInfo> matches = POLICIES.stream()
                .filter(policy -> !StringUtils.hasText(policyStatus)
                        || policy.policyStatus().equalsIgnoreCase(policyStatus.trim()))
                .toList();
        return new PolicyQueryResult(
                effectiveCustomerId, matches, matches.size(), true, Instant.now());
    }
}
