package com.xxx.insurance.asset.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.xxx.insurance.ai.workflow.model.SubAgentExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 客户资产信息查询子智能体的当前阶段边界。
 *
 * <p>真实资产微应用尚未接入，因此本 Bean 只返回明确的 Mock 结果，不生成或猜测客户资产数据。</p>
 */
public class AssetQueryAgent {

    public static final String AGENT_NAME = "asset-query-agent";

    public static final String AGENT_DESCRIPTION = "客户资产信息查询智能体扩展骨架";

    private final ReactAgent reactAgent;

    private final SkillsAgentHook skillsAgentHook;

    private final List<ToolCallback> toolCallbacks;

    /** 创建资产 Agent 骨架并保存未来 Skill/Tool 扩展点。 */
    public AssetQueryAgent(ReactAgent reactAgent,
                           SkillsAgentHook skillsAgentHook,
                           ToolCallback[] toolCallbacks) {
        this.reactAgent = reactAgent;
        this.skillsAgentHook = skillsAgentHook;
        this.toolCallbacks = List.copyOf(Arrays.asList(toolCallbacks));
    }

    /** 执行资产查询任务；当前返回不含真实客户数据的 Mock 说明。 */
    public SubAgentExecutionResult query(String query, String conversationId) {
        Instant answeredAt = Instant.now();
        String answer = "当前为资产查询 Mock 能力，尚未接入客户资产微应用。查询条件：" + query;
        return new SubAgentExecutionResult(
                AGENT_NAME,
                conversationId,
                "aqa-" + UUID.randomUUID().toString().replace("-", ""),
                answer,
                false,
                0,
                answeredAt,
                answer.length(),
                false,
                0);
    }

    /** 返回已装配但当前不会被业务 query 调用的 ReactAgent。 */
    public ReactAgent reactAgent() {
        return reactAgent;
    }

    /** 返回资产域隔离的 Skill Hook。 */
    public SkillsAgentHook skillsAgentHook() {
        return skillsAgentHook;
    }

    /** 返回当前注册的资产 Tool 快照；当前阶段为空。 */
    public List<ToolCallback> toolCallbacks() {
        return toolCallbacks;
    }
}
