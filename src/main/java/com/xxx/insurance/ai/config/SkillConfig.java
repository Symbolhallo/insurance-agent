package com.xxx.insurance.ai.config;

import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * AgentScope Skill 仓库配置。
 *
 * <p>每个业务 Agent 绑定独立 classpath 根目录。AgentScope 的 DynamicSkillMiddleware 只在
 * system prompt 中披露当前仓库的 Skill 元数据，并按需通过 Skill 工具读取完整 SKILL.md，
 * 避免产品、知识、保单和资产规则互相泄漏。</p>
 */
@Configuration
public class SkillConfig {

    public static final String PRODUCT_ANALYSIS_SKILL_REPOSITORY = "productAnalysisSkillRepository";
    public static final String KNOWLEDGE_QA_SKILL_REPOSITORY = "knowledgeQaSkillRepository";
    public static final String POLICY_QUERY_SKILL_REPOSITORY = "policyQuerySkillRepository";
    public static final String ASSET_QUERY_SKILL_REPOSITORY = "assetQuerySkillRepository";

    @Bean(value = PRODUCT_ANALYSIS_SKILL_REPOSITORY, destroyMethod = "close")
    public AgentSkillRepository productAnalysisSkillRepository() throws IOException {
        return new ClasspathSkillRepository("skills/product-analysis");
    }

    @Bean(value = KNOWLEDGE_QA_SKILL_REPOSITORY, destroyMethod = "close")
    public AgentSkillRepository knowledgeQaSkillRepository() throws IOException {
        return new ClasspathSkillRepository("skills/knowledge-qa");
    }

    @Bean(value = POLICY_QUERY_SKILL_REPOSITORY, destroyMethod = "close")
    public AgentSkillRepository policyQuerySkillRepository() throws IOException {
        return new ClasspathSkillRepository("skills/policy-query");
    }

    @Bean(value = ASSET_QUERY_SKILL_REPOSITORY, destroyMethod = "close")
    public AgentSkillRepository assetQuerySkillRepository() throws IOException {
        return new ClasspathSkillRepository("skills/asset-query");
    }
}
