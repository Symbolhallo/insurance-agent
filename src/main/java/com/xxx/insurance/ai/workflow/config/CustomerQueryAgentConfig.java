package com.xxx.insurance.ai.workflow.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.xxx.insurance.ai.config.SkillConfig;
import com.xxx.insurance.asset.agent.AssetQueryAgent;
import com.xxx.insurance.policy.agent.PolicyQueryAgent;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 配置保单与资产查询 Agent 的 Skill/Tool 可扩展骨架。 */
@Configuration
public class CustomerQueryAgentConfig {

    public static final String POLICY_QUERY_REACT_AGENT = "policyQueryReactAgent";
    public static final String POLICY_QUERY_TOOL_CALLBACKS = "policyQueryToolCallbacks";
    public static final String ASSET_QUERY_REACT_AGENT = "assetQueryReactAgent";
    public static final String ASSET_QUERY_TOOL_CALLBACKS = "assetQueryToolCallbacks";

    private static final String SCAFFOLD_INSTRUCTION = """
            当前智能体仅完成工程注册骨架，尚未接入客户数据微应用，也不得生成、猜测或返回真实客户数据。
            后续业务能力必须通过隔离 Skill 和受控 Tool 实现，并遵守客户身份、权限和审计要求。
            """;

    /** 创建保单域 Tool 注册入口；当前阶段不注册任何业务 Tool。 */
    @Bean(POLICY_QUERY_TOOL_CALLBACKS)
    public ToolCallback[] policyQueryToolCallbacks() {
        return new ToolCallback[0];
    }

    /** 创建资产域 Tool 注册入口；当前阶段不注册任何业务 Tool。 */
    @Bean(ASSET_QUERY_TOOL_CALLBACKS)
    public ToolCallback[] assetQueryToolCallbacks() {
        return new ToolCallback[0];
    }

    /** 创建保单查询 ReactAgent 骨架，当前业务 query 方法不会调用该模型实例。 */
    @Bean(POLICY_QUERY_REACT_AGENT)
    public ReactAgent policyQueryReactAgent(
            ChatModel chatModel,
            @Qualifier(SkillConfig.POLICY_QUERY_SKILLS_AGENT_HOOK) SkillsAgentHook skillsAgentHook,
            @Qualifier(POLICY_QUERY_TOOL_CALLBACKS) ToolCallback[] toolCallbacks) {
        return ReactAgent.builder()
                .name(PolicyQueryAgent.AGENT_NAME)
                .description(PolicyQueryAgent.AGENT_DESCRIPTION)
                .model(chatModel)
                .instruction(SCAFFOLD_INSTRUCTION)
                .hooks(skillsAgentHook)
                .tools(toolCallbacks)
                .enableLogging(true)
                .build();
    }

    /** 创建资产查询 ReactAgent 骨架，当前业务 query 方法不会调用该模型实例。 */
    @Bean(ASSET_QUERY_REACT_AGENT)
    public ReactAgent assetQueryReactAgent(
            ChatModel chatModel,
            @Qualifier(SkillConfig.ASSET_QUERY_SKILLS_AGENT_HOOK) SkillsAgentHook skillsAgentHook,
            @Qualifier(ASSET_QUERY_TOOL_CALLBACKS) ToolCallback[] toolCallbacks) {
        return ReactAgent.builder()
                .name(AssetQueryAgent.AGENT_NAME)
                .description(AssetQueryAgent.AGENT_DESCRIPTION)
                .model(chatModel)
                .instruction(SCAFFOLD_INSTRUCTION)
                .hooks(skillsAgentHook)
                .tools(toolCallbacks)
                .enableLogging(true)
                .build();
    }

    /** 创建保单查询业务门面，并固定 Skill/Tool 注册快照。 */
    @Bean
    public PolicyQueryAgent policyQueryAgent(
            @Qualifier(POLICY_QUERY_REACT_AGENT) ReactAgent reactAgent,
            @Qualifier(SkillConfig.POLICY_QUERY_SKILLS_AGENT_HOOK) SkillsAgentHook skillsAgentHook,
            @Qualifier(POLICY_QUERY_TOOL_CALLBACKS) ToolCallback[] toolCallbacks) {
        return new PolicyQueryAgent(reactAgent, skillsAgentHook, toolCallbacks);
    }

    /** 创建资产查询业务门面，并固定 Skill/Tool 注册快照。 */
    @Bean
    public AssetQueryAgent assetQueryAgent(
            @Qualifier(ASSET_QUERY_REACT_AGENT) ReactAgent reactAgent,
            @Qualifier(SkillConfig.ASSET_QUERY_SKILLS_AGENT_HOOK) SkillsAgentHook skillsAgentHook,
            @Qualifier(ASSET_QUERY_TOOL_CALLBACKS) ToolCallback[] toolCallbacks) {
        return new AssetQueryAgent(reactAgent, skillsAgentHook, toolCallbacks);
    }
}
