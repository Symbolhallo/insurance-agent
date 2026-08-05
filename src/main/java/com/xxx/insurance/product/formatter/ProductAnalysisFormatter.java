package com.xxx.insurance.product.formatter;

import com.xxx.insurance.product.model.ProductAnalysisData;
import com.xxx.insurance.product.model.ProductAnalysisResult;
import com.xxx.insurance.product.model.ProductInfo;
import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class ProductAnalysisFormatter {

    public ProductAnalysisResult format(ProductAnalysisData data) {
        List<ProductAnalysisResult.ProductAnalysisItem> items = data.products().stream()
                .map(this::toItem)
                .toList();

        String summary = "已基于Mock产品库完成 " + items.size() + " 个保险产品的结构化整理。";

        List<String> complianceNotes = List.of(
                "本结果仅用于产品分析智能体技术验证，不构成保险销售、投资建议或承诺收益。",
                "产品责任、费率、现金价值、免责条款等应以正式保险合同和监管披露文件为准。",
                "当前阶段未接入真实业务系统，数据来自MockProductAnalysisService。");

        return new ProductAnalysisResult(
                summary,
                items,
                data.missingProductCodes(),
                complianceNotes);
    }

    private ProductAnalysisResult.ProductAnalysisItem toItem(ProductInfo product) {
        List<String> highlights = new ArrayList<>();
        highlights.add("产品类型：" + product.productType());
        highlights.add("核心责任：" + String.join("、", product.coverageResponsibilities()));
        highlights.add("起投/最低保费：" + NumberFormat.getCurrencyInstance(Locale.CHINA).format(product.minimumPremium()));

        return new ProductAnalysisResult.ProductAnalysisItem(
                product.productCode(),
                product.productName(),
                product.productType(),
                product.insurerName(),
                highlights,
                product.riskNotes(),
                product.targetCustomer());
    }
}
