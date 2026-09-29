package com.xxx.insurance.asset.config;

import com.xxx.insurance.ai.agent.AgentScopeAgentFactory;
import com.xxx.insurance.ai.agent.AuditedReactAgentExecutor;
import com.xxx.insurance.ai.config.SkillConfig;
import com.xxx.insurance.asset.agent.AssetQueryAgent;
import com.xxx.insurance.asset.tool.AssetQueryTool;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 配置资产查询 Agent 的隔离 Skill、Tool 和 AgentScope ReAct 运行时。 */
@Configuration
public class AssetQueryAgentConfig {

    public static final String ASSET_QUERY_REACT_AGENT = "assetQueryReactAgent";

    private static final String INSTRUCTION = """
            你是客户资产信息查询智能体。当前阶段只处理固定测试客户 MOCK-CUSTOMER-001。
            回答前必须调用 customer_asset_query 获取脱敏 Mock 资产，不得依据模型记忆编造账号、余额、市值或风险等级。
            明确标注当前结果来自 Mock 数据；金额按 Tool 返回值展示，不承诺收益，不构成投资建议。
            只能使用资产域 Skill 和 Tool，不得查询保单或推断客户其他隐私信息。
            """;

    /** 创建 AgentScope 资产 HarnessAgent；Toolkit 与 Skill 仓库只暴露资产域能力。 */
    @Bean(ASSET_QUERY_REACT_AGENT)
    public HarnessAgent assetQueryReactAgent(
            AgentScopeAgentFactory factory,
            @Qualifier(SkillConfig.ASSET_QUERY_SKILL_REPOSITORY) AgentSkillRepository skills,
            AssetQueryTool tool) {
        return factory.create(AssetQueryAgent.AGENT_NAME, AssetQueryAgent.AGENT_DESCRIPTION,
                INSTRUCTION, skills, tool);
    }

    @Bean
    public AssetQueryAgent assetQueryAgent(
            @Qualifier(ASSET_QUERY_REACT_AGENT) HarnessAgent reactAgent,
            AuditedReactAgentExecutor executor) {
        return new AssetQueryAgent(reactAgent, executor);
    }
}
