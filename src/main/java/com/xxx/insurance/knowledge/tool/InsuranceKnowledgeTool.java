package com.xxx.insurance.knowledge.tool;

import com.xxx.insurance.knowledge.model.KnowledgeQueryResult;
import com.xxx.insurance.knowledge.service.KnowledgeQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 保险业务知识检索 Tool。
 */
@Component
public class InsuranceKnowledgeTool {

    public static final String TOOL_NAME = "insurance_knowledge_search";

    private static final Logger log = LoggerFactory.getLogger(InsuranceKnowledgeTool.class);

    private final KnowledgeQueryService knowledgeQueryService;

    public InsuranceKnowledgeTool(KnowledgeQueryService knowledgeQueryService) {
        this.knowledgeQueryService = knowledgeQueryService;
    }

    @Tool(
            name = TOOL_NAME,
            description = "检索已审核的保险业务基础知识Mock数据，返回知识内容和来源。")
    public KnowledgeQueryResult search(
            @ToolParam(description = "需要检索的保险业务问题") String query,
            @ToolParam(description = "可选分类：CONTRACT、COVERAGE、PARTY", required = false) String category,
            @ToolParam(description = "最大返回条数，范围1到10", required = false) Integer topK) {
        int effectiveTopK = topK == null ? 3 : topK;
        log.info("[Tool] name={} category={} topK={}", TOOL_NAME, category, effectiveTopK);
        return knowledgeQueryService.search(query, category, effectiveTopK);
    }
}
