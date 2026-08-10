package com.xxx.insurance.policy.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.xxx.insurance.ai.agent.AgentExecutionContext;
import com.xxx.insurance.ai.agent.AuditedReactAgentExecutor;
import com.xxx.insurance.ai.workflow.model.SubAgentExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.util.Arrays;
import java.util.List;

/**
 * 客户保单信息查询子智能体业务入口。
 *
 * <p>ReactAgent 必须通过保单 Tool 获取脱敏 Mock 数据，再由真实大模型组织回答。
 * 后续只需替换 PolicyQueryService 适配器，不修改 Agent 和 DAG 合同。</p>
 */
public class PolicyQueryAgent {

    public static final String AGENT_NAME = "policy-query-agent";

    public static final String AGENT_DESCRIPTION = "查询客户脱敏保单、保障额度、缴费和保单状态的智能体";

    private final ReactAgent reactAgent;

    private final SkillsAgentHook skillsAgentHook;

    private final List<ToolCallback> toolCallbacks;

    private final AuditedReactAgentExecutor agentExecutor;

    /** 创建保单 Agent 并组合 ReactAgent、Skill、Tool 与调用审计。 */
    public PolicyQueryAgent(ReactAgent reactAgent,
                            SkillsAgentHook skillsAgentHook,
                            ToolCallback[] toolCallbacks,
                            AuditedReactAgentExecutor agentExecutor) {
        this.reactAgent = reactAgent;
        this.skillsAgentHook = skillsAgentHook;
        this.toolCallbacks = List.copyOf(Arrays.asList(toolCallbacks));
        this.agentExecutor = agentExecutor;
    }

    /** 独立调用保单 Agent，真实触发模型和 Tool Calling。 */
    public SubAgentExecutionResult query(String query, String conversationId) {
        return query(query, conversationId, AgentExecutionContext.standalone(query));
    }

    /** 使用 DAG 传入的链路上下文执行保单模型调用和逐 Token 输出。 */
    public SubAgentExecutionResult query(String query,
                                         String conversationId,
                                         AgentExecutionContext executionContext) {
        return agentExecutor.execute(
                reactAgent, AGENT_NAME, "pqa-", query, conversationId, executionContext);
    }

    /** 返回保单查询 ReactAgent。 */
    public ReactAgent reactAgent() {
        return reactAgent;
    }

    /** 返回保单域隔离的 Skill Hook。 */
    public SkillsAgentHook skillsAgentHook() {
        return skillsAgentHook;
    }

    /** 返回当前注册的保单 Tool 快照。 */
    public List<ToolCallback> toolCallbacks() {
        return toolCallbacks;
    }
}
