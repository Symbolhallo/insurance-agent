package com.xxx.insurance.policy.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockPolicyQueryServiceTests {

    private final MockPolicyQueryService service = new MockPolicyQueryService();

    @Test
    void returnsOnlyInForcePoliciesWithMaskedFields() {
        var result = service.queryPolicies(MockPolicyQueryService.MOCK_CUSTOMER_ID, "IN_FORCE");

        assertThat(result.mockData()).isTrue();
        assertThat(result.totalCount()).isEqualTo(2);
        assertThat(result.policies()).allSatisfy(policy -> {
            assertThat(policy.policyStatus()).isEqualTo("IN_FORCE");
            assertThat(policy.policyNumberMasked()).contains("****");
        });
    }

    @Test
    void rejectsNonMockCustomer() {
        assertThatThrownBy(() -> service.queryPolicies("REAL-CUSTOMER", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MOCK-CUSTOMER-001");
    }
}
