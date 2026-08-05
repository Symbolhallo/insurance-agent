package com.xxx.insurance.product.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.xxx.insurance.ai.config.SkillConfig;
import com.xxx.insurance.product.formatter.ProductAnalysisFormatter;
import com.xxx.insurance.product.agent.ProductAnalysisAgent;
import com.xxx.insurance.product.service.ProductAnalysisService;
import com.xxx.insurance.product.tool.ProductAnalysisTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * 产品分析智能体装配配置。
 *
 * <p>本配置开始进入 Spring AI Alibaba Agent Framework 的原生路线：
 * 使用全局 {@link ChatModel} 作为模型能力，使用 Phase1-Task2 创建的
 * {@link SkillsAgentHook} 注入产品分析 Skill 上下文，再组装为 {@link ReactAgent}。</p>
 *
 * <p>当前仍然保持单 Agent 闭环验证阶段的边界：</p>
 *
 * <ul>
 *     <li>只注册产品分析 ToolCallback；</li>
 *     <li>不接入 Memory；</li>
 *     <li>不编排 Graph Workflow；</li>
 *     <li>只提供用于本地验证的受控产品分析 API。</li>
 * </ul>
 *
 * <p>后续阶段会在这个 ReactAgent 上继续追加 Memory、审计和更完整的模型调用入口。</p>
 */
@Configuration
public class ProductAnalysisAgentConfig {

    public static final String PRODUCT_ANALYSIS_REACT_AGENT = "productAnalysisReactAgent";

    public static final String PRODUCT_ANALYSIS_AGENT = "productAnalysisAgent";

    private static final String PRODUCT_ANALYSIS_AGENT_INSTRUCTION = """
            你是金融保险产品分析智能体，负责围绕保险产品条款、保障责任、适用客群和风险提示进行结构化分析。

            当前阶段你只能在已加载的产品分析 Skill 边界内回答问题。
            Skill 的名称、描述、适用场景和详细规则由 Spring AI Alibaba SkillsAgentHook 渐进式注入，
            不要依赖本系统提示中的硬编码 Skill 清单。

            合规要求：
            - 不承诺收益；
            - 不替代人工投顾、核保、法务或合规审查；
            - 对缺失信息明确说明，不编造产品条款；
            - 输出时区分事实、推断和建议。

            如果需要分析具体产品，必须优先使用可用的产品分析工具获取产品数据，再基于工具结果回答。
            """;

    /**
     * 将产品分析业务 Tool 转换为 Spring AI ToolCallback。
     *
     * <p>这里使用 Spring AI 的 @Tool 注解与 ToolCallbacks.from(...) 生成 ToolCallback，
     * 再交给 ReactAgent Builder。ReactAgent 会把 ToolCallback 暴露给模型，并由
     * Agent Framework 统一处理工具调用和工具结果回填。</p>
     *
     * @param productAnalysisTool 产品分析业务 Tool
     * @return 产品分析 ToolCallback 列表
     */
    @Bean
    public List<ToolCallback> productAnalysisToolCallbacks(ProductAnalysisTool productAnalysisTool) {
        return Arrays.asList(ToolCallbacks.from(productAnalysisTool));
    }

    /**
     * 装配产品分析 ReactAgent。
     *
     * <p>ReactAgent 是 Spring AI Alibaba Agent Framework 中的推理执行单元。
     * 它会使用 ChatModel 完成模型调用，并通过 hooks 接收 Skill、Memory、Human
     * Confirm 等扩展能力。本阶段注入 SkillsAgentHook 与产品分析 ToolCallback，让模型
     * 能够读取产品分析 Skill，并在需要确定性产品数据时调用 product_analysis 工具。</p>
     *
     * <p>当前只允许产品分析 Tool，不接入保单查询、资产查询、知识库检索等其他业务 Tool，
     * 避免 ProductAnalysisAgent 越过自身业务边界。</p>
     *
     * @param chatModel 全局复用的单模型 ChatModel Bean
     * @param skillsAgentHook 产品分析智能体专属 Skill Hook
     * @param productAnalysisToolCallbacks 产品分析业务 ToolCallback 列表
     * @return 产品分析智能体底层 ReactAgent
     */
    @Bean(PRODUCT_ANALYSIS_REACT_AGENT)
    public ReactAgent productAnalysisReactAgent(
            ChatModel chatModel,
            @Qualifier(SkillConfig.PRODUCT_ANALYSIS_SKILLS_AGENT_HOOK) SkillsAgentHook skillsAgentHook,
            @Qualifier("productAnalysisToolCallbacks") List<ToolCallback> productAnalysisToolCallbacks) {
        return ReactAgent.builder()
                .name(ProductAnalysisAgent.AGENT_NAME)
                .description(ProductAnalysisAgent.AGENT_DESCRIPTION)
                .model(chatModel)
                .instruction(PRODUCT_ANALYSIS_AGENT_INSTRUCTION)
                .hooks(skillsAgentHook)
                .tools(productAnalysisToolCallbacks)
                .enableLogging(true)
                .build();
    }

    /**
     * 创建业务侧产品分析智能体入口。
     *
     * <p>该 Bean 让 product 业务域不直接暴露 ReactAgent 细节。本阶段额外注入
     * ProductAnalysisService 与 ProductAnalysisFormatter，提供一个不触发模型调用的
     * 受控业务调用边界。未来如果升级为 Agent -> Model Router -> 多模型选择，
     * 或者把该边界包装成 ProductAnalysisTool，都可以优先在业务入口中演进。</p>
     *
     * @param reactAgent 产品分析 ReactAgent
     * @param skillsAgentHook 产品分析 Skill Hook
     * @param productAnalysisService 产品分析业务数据服务
     * @param productAnalysisFormatter 产品分析输出格式转换器
     * @return 产品分析业务智能体
     */
    @Bean(PRODUCT_ANALYSIS_AGENT)
    public ProductAnalysisAgent productAnalysisAgent(
            @Qualifier(PRODUCT_ANALYSIS_REACT_AGENT) ReactAgent reactAgent,
            @Qualifier(SkillConfig.PRODUCT_ANALYSIS_SKILLS_AGENT_HOOK) SkillsAgentHook skillsAgentHook,
            ProductAnalysisService productAnalysisService,
            ProductAnalysisFormatter productAnalysisFormatter) {
        return new ProductAnalysisAgent(
                reactAgent,
                skillsAgentHook,
                productAnalysisService,
                productAnalysisFormatter);
    }
}
