package com.xxx.insurance.knowledge.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 保险业务知识查询结果。
 *
 * @param query 查询文本
 * @param articles 命中的知识条目
 * @param mockData 是否为 Mock 数据
 */
@Schema(description = "保险业务知识查询结果")
public record KnowledgeQueryResult(
        @Schema(description = "查询文本")
        String query,

        @Schema(description = "命中的知识条目")
        List<KnowledgeArticle> articles,

        @Schema(description = "是否为 Mock 数据", example = "true")
        boolean mockData) {
}
