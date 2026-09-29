package com.xxx.insurance.product.config;

import com.xxx.insurance.ai.agent.AgentScopeAgentFactory;
import com.xxx.insurance.ai.agent.ReactAgentStreamingExecutor;
import com.xxx.insurance.ai.config.AiModelProperties;
import com.xxx.insurance.ai.config.SkillConfig;
import com.xxx.insurance.ai.memory.service.AgentMemoryService;
import com.xxx.insurance.product.agent.ProductAnalysisAgent;
import com.xxx.insurance.product.formatter.ProductAnalysisAnswerInspector;
import com.xxx.insurance.product.formatter.ProductAnalysisFormatter;
import com.xxx.insurance.product.service.ProductAnalysisService;
import com.xxx.insurance.product.tool.ProductAnalysisTool;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 使用 AgentScope 装配产品分析 HarnessAgent 与业务门面。 */
@Configuration
public class ProductAnalysisAgentConfig {

    public static final String PRODUCT_ANALYSIS_REACT_AGENT = "productAnalysisReactAgent";
    public static final String PRODUCT_ANALYSIS_AGENT = "productAnalysisAgent";

    private static final String INSTRUCTION = """
            你是金融保险产品分析智能体，负责围绕保险产品条款、保障责任、适用客群和风险提示进行结构化分析。
            当前阶段只能在 AgentScope 渐进加载的产品分析 Skill 边界内回答问题。
            不承诺收益，不替代人工投顾、核保、法务或合规审查；缺失信息必须明确说明。
            分析具体产品前必须调用 product_analysis 获取确定性产品数据，并区分事实、推断和建议。
            """;

    /** 创建只装载产品 Skill 与 product_analysis Tool 的 AgentScope HarnessAgent。 */
    @Bean(PRODUCT_ANALYSIS_REACT_AGENT)
    public HarnessAgent productAnalysisReactAgent(
            AgentScopeAgentFactory factory,
            @Qualifier(SkillConfig.PRODUCT_ANALYSIS_SKILL_REPOSITORY) AgentSkillRepository skills,
            ProductAnalysisTool tool) {
        return factory.create(ProductAnalysisAgent.AGENT_NAME, ProductAnalysisAgent.AGENT_DESCRIPTION,
                INSTRUCTION, skills, tool);
    }

    @Bean(PRODUCT_ANALYSIS_AGENT)
    public ProductAnalysisAgent productAnalysisAgent(
            @Qualifier(PRODUCT_ANALYSIS_REACT_AGENT) HarnessAgent reactAgent,
            ProductAnalysisService productAnalysisService,
            ProductAnalysisFormatter productAnalysisFormatter,
            ProductAnalysisAnswerInspector productAnalysisAnswerInspector,
            AgentMemoryService agentMemoryService,
            AiModelProperties aiModelProperties,
            ReactAgentStreamingExecutor streamingExecutor) {
        return new ProductAnalysisAgent(
                reactAgent, productAnalysisService, productAnalysisFormatter,
                productAnalysisAnswerInspector, agentMemoryService, aiModelProperties, streamingExecutor);
    }
}
