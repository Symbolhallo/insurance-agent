package com.xxx.insurance.knowledge.service;

import com.xxx.insurance.knowledge.model.KnowledgeArticle;
import com.xxx.insurance.knowledge.model.KnowledgeQueryResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * 本地保险知识 Mock 实现，后续由外部知识检索微服务适配器替换。
 */
@Service
public class MockKnowledgeQueryService implements KnowledgeQueryService {

    private static final List<KnowledgeArticle> ARTICLES = List.of(
            new KnowledgeArticle(
                    "K-001",
                    "保险合同犹豫期",
                    "CONTRACT",
                    "犹豫期是投保人收到保险合同后，可以按合同约定重新审视投保决定的期间。具体起算方式、期限和退保扣费规则以正式合同及监管要求为准。",
                    "Mock保险合同基础知识库"),
            new KnowledgeArticle(
                    "K-002",
                    "保险等待期",
                    "COVERAGE",
                    "等待期是保险责任生效后的一段约定期间。等待期内发生约定事故时，保险公司如何处理应以具体产品合同条款为准。",
                    "Mock保险责任基础知识库"),
            new KnowledgeArticle(
                    "K-003",
                    "现金价值与退保金",
                    "CONTRACT",
                    "现金价值是长期人身保险合同在特定时点可能具有的合同价值。退保金通常与现金价值相关，但应以合同现金价值表和退保条款为准，提前退保可能产生损失。",
                    "Mock保险合同基础知识库"),
            new KnowledgeArticle(
                    "K-004",
                    "受益人基本概念",
                    "PARTY",
                    "受益人是人身保险合同中依法或依约享有保险金请求权的人。受益人的指定、变更顺序和所需手续应遵循法律规定及保险公司规则。",
                    "Mock保险主体基础知识库"));

    @Override
    public KnowledgeQueryResult search(String query, String category, int topK) {
        if (!StringUtils.hasText(query)) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (topK < 1 || topK > 10) {
            throw new IllegalArgumentException("topK must be between 1 and 10");
        }
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        List<KnowledgeArticle> matches = ARTICLES.stream()
                .filter(article -> !StringUtils.hasText(category)
                        || article.category().equalsIgnoreCase(category.trim()))
                .filter(article -> matches(normalizedQuery, article))
                .limit(topK)
                .toList();
        return new KnowledgeQueryResult(query.trim(), matches, true);
    }

    private boolean matches(String query, KnowledgeArticle article) {
        if (query.contains(article.title().toLowerCase(Locale.ROOT))) {
            return true;
        }
        return switch (article.articleId()) {
            case "K-001" -> query.contains("犹豫期") || query.contains("冷静期");
            case "K-002" -> query.contains("等待期");
            case "K-003" -> query.contains("现金价值") || query.contains("退保金") || query.contains("退保");
            case "K-004" -> query.contains("受益人");
            default -> false;
        };
    }
}
