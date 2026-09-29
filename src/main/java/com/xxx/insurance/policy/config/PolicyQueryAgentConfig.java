package com.xxx.insurance.policy.config;

import com.xxx.insurance.ai.agent.AgentScopeAgentFactory;
import com.xxx.insurance.ai.agent.AuditedReactAgentExecutor;
import com.xxx.insurance.ai.config.SkillConfig;
import com.xxx.insurance.policy.agent.PolicyQueryAgent;
import com.xxx.insurance.policy.tool.PolicyQueryTool;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 配置保单查询 Agent 的隔离 Skill、Tool 和 AgentScope ReAct 运行时。 */
@Configuration
public class PolicyQueryAgentConfig {

    public static final String POLICY_QUERY_REACT_AGENT = "policyQueryReactAgent";

    private static final String INSTRUCTION = """
            你是客户保单信息查询智能体。当前阶段只处理固定测试客户 MOCK-CUSTOMER-001。
            回答前必须调用 customer_policy_query 获取脱敏 Mock 保单，不得依据模型记忆编造保单号、金额、状态或日期。
            明确标注当前结果来自 Mock 数据；区分保单事实与一般性说明，不代替保险公司正式查询结果。
            只能使用保单域 Skill 和 Tool，不得查询资产或推断客户其他隐私信息。
            """;

    /** 创建 AgentScope 保单 HarnessAgent；Toolkit 与 Skill 仓库只暴露保单域能力。 */
    @Bean(POLICY_QUERY_REACT_AGENT)
    public HarnessAgent policyQueryReactAgent(
            AgentScopeAgentFactory factory,
            @Qualifier(SkillConfig.POLICY_QUERY_SKILL_REPOSITORY) AgentSkillRepository skills,
            PolicyQueryTool tool) {
        return factory.create(PolicyQueryAgent.AGENT_NAME, PolicyQueryAgent.AGENT_DESCRIPTION,
                INSTRUCTION, skills, tool);
    }

    @Bean
    public PolicyQueryAgent policyQueryAgent(
            @Qualifier(POLICY_QUERY_REACT_AGENT) HarnessAgent reactAgent,
            AuditedReactAgentExecutor executor) {
        return new PolicyQueryAgent(reactAgent, executor);
    }
}
