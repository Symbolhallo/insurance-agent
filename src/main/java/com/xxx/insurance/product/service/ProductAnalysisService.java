package com.xxx.insurance.product.service;

import com.xxx.insurance.product.model.ProductAnalysisData;
import com.xxx.insurance.product.model.ProductAnalysisRequest;

public interface ProductAnalysisService {

    ProductAnalysisData queryProductAnalysisData(ProductAnalysisRequest request);
}
