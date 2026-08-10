package com.xxx.insurance.product.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.retrieval.model.RetrievalCallRecord;
import com.xxx.insurance.ai.retrieval.service.RetrievalCallRecorder;
import com.xxx.insurance.product.model.ProductCandidate;
import com.xxx.insurance.product.model.ProductInfo;
import com.xxx.insurance.product.model.ProductRecallExecutionContext;
import com.xxx.insurance.product.model.ProductRecallRequest;
import com.xxx.insurance.product.model.ProductRecallResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Phase1 产品召回 Mock 实现。
 */
@Service
public class MockProductRecallService implements ProductRecallService {

    private static final Logger log = LoggerFactory.getLogger(MockProductRecallService.class);

    private static final int DEFAULT_TOP_K = 3;

    private final MockProductCatalog productCatalog;

    private final RetrievalCallRecorder retrievalCallRecorder;

    private final ObjectMapper objectMapper;

    public MockProductRecallService(MockProductCatalog productCatalog,
                                    RetrievalCallRecorder retrievalCallRecorder,
                                    ObjectMapper objectMapper) {
        this.productCatalog = productCatalog;
        this.retrievalCallRecorder = retrievalCallRecorder;
        this.objectMapper = objectMapper;
    }

    @Override
    public ProductRecallResult recall(ProductRecallRequest request, ProductRecallExecutionContext context) {
        String query = normalizeQuery(request.query());
        int topK = request.topK() == null ? DEFAULT_TOP_K : request.topK();
        if (topK < 1 || topK > 10) {
            throw new IllegalArgumentException("topK must be between 1 and 10");
        }

        long startNanos = System.nanoTime();
        String retrievalCallId = "prc-" + UUID.randomUUID().toString().replace("-", "");
        List<ProductCandidate> candidates = productCatalog.products().values().stream()
                .map(product -> toCandidate(product, query))
                .sorted(Comparator.comparing(ProductCandidate::score).reversed()
                        .thenComparing(ProductCandidate::productCode))
                .limit(topK)
                .toList();
        long durationMs = elapsedMillis(startNanos);
        ProductRecallResult result = new ProductRecallResult(
                retrievalCallId,
                query,
                topK,
                candidates,
                true,
                durationMs,
                Instant.now());

        retrievalCallRecorder.record(new RetrievalCallRecord(
                retrievalCallId,
                context == null ? request.conversationId() : context.conversationId(),
                null,
                context == null ? null : context.workflowInstanceId(),
                "product",
                query,
                topK,
                toJson(request.filters() == null ? Map.of() : request.filters()),
                toJson(result),
                durationMs,
                "SUCCESS",
                null,
                result.recalledAt()));
        log.info("[Tool] name=product-recall status=success retrievalCallId={} conversationId={} "
                        + "workflowInstanceId={} candidateCount={} mockData=true durationMs={}",
                retrievalCallId,
                context == null ? request.conversationId() : context.conversationId(),
                context == null ? null : context.workflowInstanceId(),
                candidates.size(),
                durationMs);
        return result;
    }

    private ProductCandidate toCandidate(ProductInfo product, String query) {
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        Match match = switch (product.productCode()) {
            case "PA-001" -> match(normalizedQuery, List.of("pa-001", "终身", "寿险", "传承", "身故"),
                    "查询命中终身寿险或资产传承需求");
            case "PA-002" -> match(normalizedQuery, List.of("pa-002", "重疾", "重大疾病", "健康", "轻症"),
                    "查询命中重大疾病保障需求");
            case "PA-003" -> match(normalizedQuery, List.of("pa-003", "养老", "年金", "退休"),
                    "查询命中养老年金或退休现金流需求");
            default -> new Match(new BigDecimal("0.70"), "Mock 产品目录默认候选");
        };
        return new ProductCandidate(
                product.productCode(),
                product.productName(),
                product.productType(),
                product.insurerName(),
                match.score(),
                match.reason());
    }

    private Match match(String query, List<String> keywords, String reason) {
        boolean matched = keywords.stream().anyMatch(query::contains);
        return matched
                ? new Match(new BigDecimal("0.98"), reason)
                : new Match(new BigDecimal("0.80"), "Mock 产品目录通用候选");
    }

    private String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        return query.trim();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize product recall audit payload", ex);
        }
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private record Match(BigDecimal score, String reason) {
    }
}
