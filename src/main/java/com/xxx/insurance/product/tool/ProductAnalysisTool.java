package com.xxx.insurance.product.tool;

import com.xxx.insurance.product.formatter.ProductAnalysisFormatter;
import com.xxx.insurance.product.model.ProductAnalysisRequest;
import com.xxx.insurance.product.model.ProductAnalysisResult;
import com.xxx.insurance.product.service.ProductAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 产品分析业务 Tool。
 *
 * <p>Tool 是 Agent 执行确定性业务动作的边界。ReactAgent 负责根据用户问题和 Skill
 * 说明提取结构化参数；本 Tool 只接收结构化参数并查询产品域 Service，不解析原始用户问题。</p>
 *
 * <p>当前实现仍然使用 MockProductAnalysisService，目的是验证 Spring AI Alibaba
 * ReactAgent -> ToolCallback -> Service -> Formatter 的链路。后续替换真实业务系统时，
 * 优先替换 ProductAnalysisService 实现，而不是改动 Agent 编排。</p>
 */
@Component
public class ProductAnalysisTool {

    public static final String TOOL_NAME = "product_analysis";

    private static final Logger log = LoggerFactory.getLogger(ProductAnalysisTool.class);

    private final ProductAnalysisService productAnalysisService;

    private final ProductAnalysisFormatter productAnalysisFormatter;

    public ProductAnalysisTool(ProductAnalysisService productAnalysisService,
                               ProductAnalysisFormatter productAnalysisFormatter) {
        this.productAnalysisService = productAnalysisService;
        this.productAnalysisFormatter = productAnalysisFormatter;
    }

    /**
     * 查询并格式化保险产品分析数据。
     *
     * @param productCodes 产品编码列表，例如 PA-001、PA-002
     * @param customerProfile 客户画像或需求描述
     * @param analysisDimensions 分析维度，例如 coverage、risk、premium
     * @return 产品分析结构化结果
     */
    @Tool(
            name = TOOL_NAME,
            description = "根据产品编码、客户画像和分析维度查询保险产品Mock数据，并返回结构化产品分析结果。")
    public ProductAnalysisResult analyzeProducts(
            @ToolParam(description = "产品编码列表，例如 PA-001、PA-002") List<String> productCodes,
            @ToolParam(description = "客户画像或需求描述", required = false) String customerProfile,
            @ToolParam(description = "分析维度列表，例如 coverage、risk、premium", required = false)
            List<String> analysisDimensions) {
        ProductAnalysisRequest request = new ProductAnalysisRequest(
                normalizeProductCodes(productCodes),
                customerProfile,
                analysisDimensions == null ? List.of() : analysisDimensions);

        log.info("[Tool] name={} productCodes={} dimensions={}",
                TOOL_NAME,
                request.productCodes(),
                request.analysisDimensions());

        return productAnalysisFormatter.format(productAnalysisService.queryProductAnalysisData(request));
    }

    private List<String> normalizeProductCodes(List<String> productCodes) {
        if (productCodes == null) {
            throw new IllegalArgumentException("At least one product code is required");
        }
        List<String> normalized = productCodes.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("At least one product code is required");
        }
        return normalized;
    }
}
