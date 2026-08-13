package com.xxx.insurance.ai.config;

import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.classpath.ClasspathSkillRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI Alibaba Skill基础设施配置。
 *
 * <p>Skill 是 Spring AI Alibaba Agent Framework 向 ReactAgent 注入领域能力上下文的机制。
 * 它不是普通的 Markdown 文件读取器，而是由 SkillRegistry 管理的一组领域能力说明，
 * 四个领域 ReactAgent 通过各自的 SkillsAgentHook 获得以下能力：</p>
 *
 * <ul>
 *     <li>在模型系统提示词中注入可用 Skill 列表；</li>
 *     <li>向模型暴露 read_skill/search_skills/disable_skill 等 Skill 管理工具；</li>
 *     <li>在模型选择某个 Skill 后，按 Skill 规则增强推理上下文。</li>
 * </ul>
 *
 * <p>本配置只创建四组相互隔离的 Registry/Hook，不创建 ReactAgent 或业务 Tool。各领域 config 将专属
 * Hook、ToolCallback 和共享 ChatModel 组装进对应 ReactAgent，避免共享 {@code skills} 根目录导致规则、
 * read_skill 结果或工具能力跨产品、知识、保单、资产子智能体泄漏。</p>
 */
@Configuration
public class SkillConfig {

    public static final String PRODUCT_ANALYSIS_SKILL_REGISTRY = "productAnalysisSkillRegistry";

    public static final String PRODUCT_ANALYSIS_SKILLS_AGENT_HOOK = "productAnalysisSkillsAgentHook";

    public static final String KNOWLEDGE_QA_SKILL_REGISTRY = "knowledgeQaSkillRegistry";

    public static final String KNOWLEDGE_QA_SKILLS_AGENT_HOOK = "knowledgeQaSkillsAgentHook";

    public static final String POLICY_QUERY_SKILL_REGISTRY = "policyQuerySkillRegistry";

    public static final String POLICY_QUERY_SKILLS_AGENT_HOOK = "policyQuerySkillsAgentHook";

    public static final String ASSET_QUERY_SKILL_REGISTRY = "assetQuerySkillRegistry";

    public static final String ASSET_QUERY_SKILLS_AGENT_HOOK = "assetQuerySkillsAgentHook";

    /**
     * ProductAnalysisAgent 专属 Skill 根路径。
     *
     * <p>不要使用共享根路径 {@code skills}，否则 policy、knowledge、asset 等子智能体
     * 的 Skill 会被产品分析智能体误加载。这里将 Registry 限定到
     * {@code classpath:skills/product-analysis}，确保产品分析智能体只能看到自己的
     * limited-product-analysis 与 batch-product-analysis。</p>
     */
    private static final String PRODUCT_ANALYSIS_SKILL_CLASSPATH = "skills/product-analysis";

    private static final String KNOWLEDGE_QA_SKILL_CLASSPATH = "skills/knowledge-qa";

    private static final String POLICY_QUERY_SKILL_CLASSPATH = "skills/policy-query";

    private static final String ASSET_QUERY_SKILL_CLASSPATH = "skills/asset-query";

    /**
     * 创建产品分析智能体专属 SkillRegistry。
     *
     * <p>ClasspathSkillRegistry 会从 classpath 扫描 Skill 目录。开发态读取
     * {@code src/main/resources}，打包后读取应用 Jar 内资源；框架会解析每个 Skill 目录下的
     * SKILL.md 并生成 SkillMetadata。ProductAnalysisAgent 的 ReactAgent 注入这个 Registry 对应的
     * SkillsAgentHook 后，只会渐进披露产品分析 Skill 元数据和详细规则。</p>
     *
     * @return 只加载 product-analysis Skill 根目录的 SkillRegistry
     */
    @Bean(PRODUCT_ANALYSIS_SKILL_REGISTRY)
    public SkillRegistry productAnalysisSkillRegistry() {
        return ClasspathSkillRegistry.builder()
                .classpathPath(PRODUCT_ANALYSIS_SKILL_CLASSPATH)
                .build();
    }

    /**
     * 创建产品分析智能体专属 SkillsAgentHook。
     *
     * <p>SkillsAgentHook 是 SkillRegistry 与 ReactAgent 之间的桥。ReactAgent 运行时，
     * 该 Hook 会在 Agent 执行前提供 Skill 列表和 Skill 读取工具，并通过
     * SkillsInterceptor 将 Skill 说明注入模型调用链。</p>
     *
     * <p>业务 ToolCallback 由产品 Agent config 直接注册到 ReactAgent，不通过 groupedTools 动态启停；
     * Skill 负责渐进披露任务规则，Tool 负责执行确定性产品事实查询，两者职责分离。</p>
     *
     * @param skillRegistry 产品分析智能体专属 SkillRegistry
     * @return 产品分析 ReactAgent 当前使用的 SkillsAgentHook
     */
    @Bean(PRODUCT_ANALYSIS_SKILLS_AGENT_HOOK)
    public SkillsAgentHook productAnalysisSkillsAgentHook(
            @Qualifier(PRODUCT_ANALYSIS_SKILL_REGISTRY) SkillRegistry skillRegistry) {
        return SkillsAgentHook.builder()
                .skillRegistry(skillRegistry)
                .autoReload(false)
                .build();
    }

    /**
     * 创建知识问答智能体专属 SkillRegistry。
     *
     * <p>知识 Skill 使用独立 classpath 根目录，避免 ProductAnalysisAgent 和
     * KnowledgeQAAgent 相互加载对方的领域规则与工具声明。</p>
     */
    @Bean(KNOWLEDGE_QA_SKILL_REGISTRY)
    public SkillRegistry knowledgeQaSkillRegistry() {
        return ClasspathSkillRegistry.builder()
                .classpathPath(KNOWLEDGE_QA_SKILL_CLASSPATH)
                .build();
    }

    /**
     * 将知识问答 SkillRegistry 接入 KnowledgeQAAgent 的 ReactAgent 生命周期。
     */
    @Bean(KNOWLEDGE_QA_SKILLS_AGENT_HOOK)
    public SkillsAgentHook knowledgeQaSkillsAgentHook(
            @Qualifier(KNOWLEDGE_QA_SKILL_REGISTRY) SkillRegistry skillRegistry) {
        return SkillsAgentHook.builder()
                .skillRegistry(skillRegistry)
                .autoReload(false)
                .build();
    }

    /** 创建保单查询 Agent 专属 SkillRegistry，避免加载其他子智能体规则。 */
    @Bean(POLICY_QUERY_SKILL_REGISTRY)
    public SkillRegistry policyQuerySkillRegistry() {
        return ClasspathSkillRegistry.builder()
                .classpathPath(POLICY_QUERY_SKILL_CLASSPATH)
                .build();
    }

    /** 将保单 SkillRegistry 接入保单 ReactAgent，提供渐进披露和 read_skill 能力。 */
    @Bean(POLICY_QUERY_SKILLS_AGENT_HOOK)
    public SkillsAgentHook policyQuerySkillsAgentHook(
            @Qualifier(POLICY_QUERY_SKILL_REGISTRY) SkillRegistry skillRegistry) {
        return SkillsAgentHook.builder()
                .skillRegistry(skillRegistry)
                .autoReload(false)
                .build();
    }

    /** 创建资产查询 Agent 专属 SkillRegistry，避免加载其他子智能体规则。 */
    @Bean(ASSET_QUERY_SKILL_REGISTRY)
    public SkillRegistry assetQuerySkillRegistry() {
        return ClasspathSkillRegistry.builder()
                .classpathPath(ASSET_QUERY_SKILL_CLASSPATH)
                .build();
    }

    /** 将资产 SkillRegistry 接入资产 ReactAgent，提供渐进披露和 read_skill 能力。 */
    @Bean(ASSET_QUERY_SKILLS_AGENT_HOOK)
    public SkillsAgentHook assetQuerySkillsAgentHook(
            @Qualifier(ASSET_QUERY_SKILL_REGISTRY) SkillRegistry skillRegistry) {
        return SkillsAgentHook.builder()
                .skillRegistry(skillRegistry)
                .autoReload(false)
                .build();
    }
}
