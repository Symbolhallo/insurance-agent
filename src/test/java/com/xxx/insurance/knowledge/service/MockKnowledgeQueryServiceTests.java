package com.xxx.insurance.knowledge.service;

import com.xxx.insurance.knowledge.model.KnowledgeQueryResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockKnowledgeQueryServiceTests {

    private final MockKnowledgeQueryService service = new MockKnowledgeQueryService();

    @Test
    void returnsReviewedKnowledgeForCoolingOffPeriod() {
        KnowledgeQueryResult result = service.search("保险合同的犹豫期是什么", null, 3);

        assertThat(result.mockData()).isTrue();
        assertThat(result.articles()).singleElement().satisfies(article -> {
            assertThat(article.articleId()).isEqualTo("K-001");
            assertThat(article.title()).contains("犹豫期");
            assertThat(article.source()).isNotBlank();
        });
    }

    @Test
    void returnsEmptyArticlesWhenKnowledgeBaseDoesNotMatch() {
        KnowledgeQueryResult result = service.search("完全未知的保险概念", null, 3);

        assertThat(result.articles()).isEmpty();
    }
}
