package com.xxx.insurance.asset.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockAssetQueryServiceTests {

    private final MockAssetQueryService service = new MockAssetQueryService();

    @Test
    void returnsMaskedPositionsAndDeterministicTotal() {
        var result = service.queryAssets(MockAssetQueryService.MOCK_CUSTOMER_ID, null);

        assertThat(result.mockData()).isTrue();
        assertThat(result.totalMarketValue()).isEqualByComparingTo(new BigDecimal("612300.75"));
        assertThat(result.positions()).hasSize(3)
                .allSatisfy(position -> assertThat(position.accountNumberMasked()).contains("****"));
    }

    @Test
    void filtersSingleAssetTypeAndRejectsNonMockCustomer() {
        var deposits = service.queryAssets(MockAssetQueryService.MOCK_CUSTOMER_ID, "DEPOSIT");
        assertThat(deposits.positions()).singleElement().satisfies(position ->
                assertThat(position.assetType()).isEqualTo("DEPOSIT"));

        assertThatThrownBy(() -> service.queryAssets("REAL-CUSTOMER", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MOCK-CUSTOMER-001");
    }
}
