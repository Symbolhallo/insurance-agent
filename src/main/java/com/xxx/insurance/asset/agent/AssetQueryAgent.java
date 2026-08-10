package com.xxx.insurance.asset.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.xxx.insurance.ai.agent.AgentExecutionContext;
import com.xxx.insurance.ai.agent.AuditedReactAgentExecutor;
import com.xxx.insurance.ai.workflow.model.SubAgentExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.util.Arrays;
import java.util.List;

/**
 * 客户资产信息查询子智能体业务入口。
 *
 * <p>ReactAgent 必须通过资产 Tool 获取脱敏 Mock 数据，再由真实大模型组织回答。</p>
 */
public class AssetQueryAgent {

    public static final String AGENT_NAME = "asset-query-agent";

    public static final String AGENT_DESCRIPTION = "查询客户脱敏资产余额、持仓结构、风险等级和流动性的智能体";

    private final ReactAgent reactAgent;

    private final SkillsAgentHook skillsAgentHook;

    private final List<ToolCallback> toolCallbacks;

    private final AuditedReactAgentExecutor agentExecutor;

    /** 创建资产 Agent 并组合 ReactAgent、Skill、Tool 与调用审计。 */
    public AssetQueryAgent(ReactAgent reactAgent,
                           SkillsAgentHook skillsAgentHook,
                           ToolCallback[] toolCallbacks,
                           AuditedReactAgentExecutor agentExecutor) {
        this.reactAgent = reactAgent;
        this.skillsAgentHook = skillsAgentHook;
        this.toolCallbacks = List.copyOf(Arrays.asList(toolCallbacks));
        this.agentExecutor = agentExecutor;
    }

    /** 独立调用资产 Agent，真实触发模型和 Tool Calling。 */
    public SubAgentExecutionResult query(String query, String conversationId) {
        return query(query, conversationId, AgentExecutionContext.standalone(query));
    }

    /** 使用 DAG 传入的链路上下文执行资产模型调用和逐 Token 输出。 */
    public SubAgentExecutionResult query(String query,
                                         String conversationId,
                                         AgentExecutionContext executionContext) {
        return agentExecutor.execute(
                reactAgent, AGENT_NAME, "aqa-", query, conversationId, executionContext);
    }

    /** 返回资产查询 ReactAgent。 */
    public ReactAgent reactAgent() {
        return reactAgent;
    }

    /** 返回资产域隔离的 Skill Hook。 */
    public SkillsAgentHook skillsAgentHook() {
        return skillsAgentHook;
    }

    /** 返回当前注册的资产 Tool 快照。 */
    public List<ToolCallback> toolCallbacks() {
        return toolCallbacks;
    }
}
