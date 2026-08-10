package com.xxx.insurance.product.service;

import com.xxx.insurance.product.model.ProductAnalysisData;
import com.xxx.insurance.product.model.ProductAnalysisRequest;
import com.xxx.insurance.product.model.ProductInfo;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class MockProductAnalysisService implements ProductAnalysisService {

    private final Map<String, ProductInfo> productStore;

    public MockProductAnalysisService(MockProductCatalog productCatalog) {
        this.productStore = productCatalog.products();
    }

    @Override
    public ProductAnalysisData queryProductAnalysisData(ProductAnalysisRequest request) {
        List<String> productCodes = request.productCodes().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .distinct()
                .toList();

        List<ProductInfo> products = productCodes.stream()
                .map(productStore::get)
                .filter(Objects::nonNull)
                .toList();

        List<String> missingProductCodes = productCodes.stream()
                .filter(code -> !productStore.containsKey(code))
                .toList();

        ProductAnalysisRequest normalizedRequest = new ProductAnalysisRequest(
                productCodes,
                request.customerProfile(),
                request.analysisDimensions());

        return new ProductAnalysisData(normalizedRequest, products, missingProductCodes);
    }
}
