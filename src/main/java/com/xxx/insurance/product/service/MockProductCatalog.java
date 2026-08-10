package com.xxx.insurance.product.service;

import com.xxx.insurance.product.model.ProductInfo;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/**
 * Phase1 共用 Mock 产品目录。
 */
@Component
public class MockProductCatalog {

    private final Map<String, ProductInfo> products = buildProducts();

    public Map<String, ProductInfo> products() {
        return products;
    }

    private static Map<String, ProductInfo> buildProducts() {
        Map<String, ProductInfo> products = new LinkedHashMap<>();
        products.put("PA-001", new ProductInfo(
                "PA-001", "安享一生终身寿险", "终身寿险", "示例人寿保险股份有限公司",
                List.of("身故保障", "全残保障", "现金价值积累"),
                "适合关注长期保障与资产传承规划的客户", "5年/10年/20年",
                new BigDecimal("10000"),
                List.of("退保可能产生损失", "现金价值变化以正式合同和利益演示为准")));
        products.put("PA-002", new ProductInfo(
                "PA-002", "康健无忧重大疾病保险", "重大疾病保险", "示例健康保险股份有限公司",
                List.of("重大疾病保障", "轻症疾病保障", "被保险人豁免"),
                "适合关注重大疾病风险转移的家庭经济支柱", "10年/20年/30年",
                new BigDecimal("3000"),
                List.of("等待期内出险责任受限", "既往症及免责条款需重点核对")));
        products.put("PA-003", new ProductInfo(
                "PA-003", "稳盈养老年金保险", "养老年金保险", "示例养老保险股份有限公司",
                List.of("养老年金领取", "身故保障", "保证领取期间"),
                "适合有长期养老现金流规划需求的客户", "趸交/3年/5年",
                new BigDecimal("20000"),
                List.of("年金领取安排需结合现金流需求", "不应将演示收益理解为承诺收益")));
        return Collections.unmodifiableMap(products);
    }
}
