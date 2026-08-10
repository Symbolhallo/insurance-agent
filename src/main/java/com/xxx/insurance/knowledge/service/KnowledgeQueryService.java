package com.xxx.insurance.knowledge.service;

import com.xxx.insurance.knowledge.model.KnowledgeQueryResult;

/**
 * 保险业务知识检索边界。
 */
public interface KnowledgeQueryService {

    KnowledgeQueryResult search(String query, String category, int topK);
}
