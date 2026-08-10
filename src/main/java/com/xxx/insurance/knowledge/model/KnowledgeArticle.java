package com.xxx.insurance.knowledge.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 保险业务知识条目。
 *
 * @param articleId 知识条目编号
 * @param title 标题
 * @param category 分类
 * @param content 已审核的知识内容
 * @param source 来源说明
 */
@Schema(description = "保险业务知识条目")
public record KnowledgeArticle(
        @Schema(description = "知识条目编号", example = "K-001")
        String articleId,

        @Schema(description = "知识标题", example = "保险合同犹豫期")
        String title,

        @Schema(description = "知识分类", example = "CONTRACT")
        String category,

        @Schema(description = "已审核的知识内容")
        String content,

        @Schema(description = "知识来源说明")
        String source) {
}
