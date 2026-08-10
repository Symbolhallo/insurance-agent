package com.xxx.insurance.product.controller;

import com.xxx.insurance.common.result.ApiResponse;
import com.xxx.insurance.product.model.ProductRecallExecutionContext;
import com.xxx.insurance.product.model.ProductRecallRequest;
import com.xxx.insurance.product.model.ProductRecallResult;
import com.xxx.insurance.product.service.ProductRecallService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 产品召回 API。
 */
@Tag(name = "ProductRecall", description = "产品候选召回接口")
@RestController
@RequestMapping("/api/v1/products")
public class ProductRecallController {

    private final ProductRecallService productRecallService;

    public ProductRecallController(ProductRecallService productRecallService) {
        this.productRecallService = productRecallService;
    }

    @Operation(summary = "召回产品候选", description = "当前阶段返回确定性的 Mock 产品候选。")
    @PostMapping("/recall")
    public ApiResponse<ProductRecallResult> recall(@Valid @RequestBody ProductRecallRequest request) {
        ProductRecallExecutionContext context = new ProductRecallExecutionContext(request.conversationId(), null);
        return ApiResponse.success(productRecallService.recall(request, context));
    }
}
