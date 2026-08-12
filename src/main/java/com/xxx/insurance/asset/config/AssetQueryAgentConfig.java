package com.xxx.insurance.asset.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.xxx.insurance.ai.agent.AuditedReactAgentExecutor;
import com.xxx.insurance.ai.config.AgentSafetyConfig;
import com.xxx.insurance.ai.config.SkillConfig;
import com.xxx.insurance.asset.agent.AssetQueryAgent;
import com.xxx.insurance.asset.tool.AssetQueryTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 配置资产查询 Agent 的隔离 Skill、Tool 和真实模型调用。 */
@Configuration
public class AssetQueryAgentConfig {

    public static final String ASSET_QUERY_REACT_AGENT = "assetQueryReactAgent";

    public static final String ASSET_QUERY_TOOL_CALLBACKS = "assetQueryToolCallbacks";

    private static final String ASSET_QUERY_INSTRUCTION = """
            你是客户资产信息查询智能体。当前阶段只处理固定测试客户 MOCK-CUSTOMER-001。
            回答前必须调用 customer_asset_query 获取脱敏 Mock 资产，不得依据模型记忆编造账号、余额、市值或风险等级。
            明确标注当前结果来自 Mock 数据；金额按 Tool 返回值展示，不承诺收益，不构成投资建议。
            只能使用资产域 Skill 和 Tool，不得查询保单或推断客户其他隐私信息。
            """;

    /** 将资产查询 Tool 转换为仅供 AssetQueryAgent 使用的 ToolCallback。 */
    @Bean(ASSET_QUERY_TOOL_CALLBACKS)
    public ToolCallback[] assetQueryToolCallbacks(AssetQueryTool assetQueryTool) {
        return ToolCallbacks.from(assetQueryTool);
    }

    /** 创建资产查询 ReactAgent，模型通过 ReAct 循环调用资产 Mock Tool 后生成回答。 */
    @Bean(ASSET_QUERY_REACT_AGENT)
    public ReactAgent assetQueryReactAgent(
            ChatModel chatModel,
            @Qualifier(SkillConfig.ASSET_QUERY_SKILLS_AGENT_HOOK) SkillsAgentHook skillsAgentHook,
            @Qualifier(AgentSafetyConfig.DOMAIN_AGENT_MODEL_CALL_LIMIT_HOOK)
            ModelCallLimitHook modelCallLimitHook,
            @Qualifier(ASSET_QUERY_TOOL_CALLBACKS) ToolCallback[] toolCallbacks) {
        return ReactAgent.builder()
                .name(AssetQueryAgent.AGENT_NAME)
                .description(AssetQueryAgent.AGENT_DESCRIPTION)
                .model(chatModel)
                .instruction(ASSET_QUERY_INSTRUCTION)
                .hooks(skillsAgentHook, modelCallLimitHook)
                .tools(toolCallbacks)
                .enableLogging(true)
                .build();
    }

    /** 创建资产查询业务门面，并接入真实模型、逐 Token SSE 和调用审计。 */
    @Bean
    public AssetQueryAgent assetQueryAgent(
            @Qualifier(ASSET_QUERY_REACT_AGENT) ReactAgent reactAgent,
            @Qualifier(SkillConfig.ASSET_QUERY_SKILLS_AGENT_HOOK) SkillsAgentHook skillsAgentHook,
            @Qualifier(ASSET_QUERY_TOOL_CALLBACKS) ToolCallback[] toolCallbacks,
            AuditedReactAgentExecutor agentExecutor) {
        return new AssetQueryAgent(reactAgent, skillsAgentHook, toolCallbacks, agentExecutor);
    }
}
