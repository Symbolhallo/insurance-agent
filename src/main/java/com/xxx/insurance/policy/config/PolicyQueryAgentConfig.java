package com.xxx.insurance.policy.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.xxx.insurance.ai.agent.AuditedReactAgentExecutor;
import com.xxx.insurance.ai.config.AgentSafetyConfig;
import com.xxx.insurance.ai.config.SkillConfig;
import com.xxx.insurance.policy.agent.PolicyQueryAgent;
import com.xxx.insurance.policy.tool.PolicyQueryTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 配置保单查询 Agent 的隔离 Skill、Tool 和真实模型调用。 */
@Configuration
public class PolicyQueryAgentConfig {

    public static final String POLICY_QUERY_REACT_AGENT = "policyQueryReactAgent";

    public static final String POLICY_QUERY_TOOL_CALLBACKS = "policyQueryToolCallbacks";

    private static final String POLICY_QUERY_INSTRUCTION = """
            你是客户保单信息查询智能体。当前阶段只处理固定测试客户 MOCK-CUSTOMER-001。
            回答前必须调用 customer_policy_query 获取脱敏 Mock 保单，不得依据模型记忆编造保单号、金额、状态或日期。
            明确标注当前结果来自 Mock 数据；区分保单事实与一般性说明，不代替保险公司正式查询结果。
            只能使用保单域 Skill 和 Tool，不得查询资产或推断客户其他隐私信息。
            """;

    /** 将保单查询 Tool 转换为仅供 PolicyQueryAgent 使用的 ToolCallback。 */
    @Bean(POLICY_QUERY_TOOL_CALLBACKS)
    public ToolCallback[] policyQueryToolCallbacks(PolicyQueryTool policyQueryTool) {
        return ToolCallbacks.from(policyQueryTool);
    }

    /** 创建保单查询 ReactAgent，模型通过 ReAct 循环调用保单 Mock Tool 后生成回答。 */
    @Bean(POLICY_QUERY_REACT_AGENT)
    public ReactAgent policyQueryReactAgent(
            ChatModel chatModel,
            @Qualifier(SkillConfig.POLICY_QUERY_SKILLS_AGENT_HOOK) SkillsAgentHook skillsAgentHook,
            @Qualifier(AgentSafetyConfig.DOMAIN_AGENT_MODEL_CALL_LIMIT_HOOK)
            ModelCallLimitHook modelCallLimitHook,
            @Qualifier(POLICY_QUERY_TOOL_CALLBACKS) ToolCallback[] toolCallbacks) {
        return ReactAgent.builder()
                .name(PolicyQueryAgent.AGENT_NAME)
                .description(PolicyQueryAgent.AGENT_DESCRIPTION)
                .model(chatModel)
                .instruction(POLICY_QUERY_INSTRUCTION)
                .hooks(skillsAgentHook, modelCallLimitHook)
                .tools(toolCallbacks)
                .enableLogging(true)
                .build();
    }

    /** 创建保单查询业务门面，并接入真实模型、逐 Token SSE 和调用审计。 */
    @Bean
    public PolicyQueryAgent policyQueryAgent(
            @Qualifier(POLICY_QUERY_REACT_AGENT) ReactAgent reactAgent,
            @Qualifier(SkillConfig.POLICY_QUERY_SKILLS_AGENT_HOOK) SkillsAgentHook skillsAgentHook,
            @Qualifier(POLICY_QUERY_TOOL_CALLBACKS) ToolCallback[] toolCallbacks,
            AuditedReactAgentExecutor agentExecutor) {
        return new PolicyQueryAgent(reactAgent, skillsAgentHook, toolCallbacks, agentExecutor);
    }
}
