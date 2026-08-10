package com.xxx.insurance.policy.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.xxx.insurance.ai.workflow.model.SubAgentExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 客户保单信息查询子智能体的当前阶段边界。
 *
 * <p>真实保单微应用尚未接入，因此本 Bean 只返回明确的 Mock 结果，不生成或猜测客户保单数据。
 * 动态 DAG 只依赖该稳定调用合同，后续替换微应用适配器不需要修改调度器。</p>
 */
public class PolicyQueryAgent {

    public static final String AGENT_NAME = "policy-query-agent";

    public static final String AGENT_DESCRIPTION = "客户保单信息查询智能体扩展骨架";

    private final ReactAgent reactAgent;

    private final SkillsAgentHook skillsAgentHook;

    private final List<ToolCallback> toolCallbacks;

    /** 创建保单 Agent 骨架并保存未来 Skill/Tool 扩展点。 */
    public PolicyQueryAgent(ReactAgent reactAgent,
                            SkillsAgentHook skillsAgentHook,
                            ToolCallback[] toolCallbacks) {
        this.reactAgent = reactAgent;
        this.skillsAgentHook = skillsAgentHook;
        this.toolCallbacks = List.copyOf(Arrays.asList(toolCallbacks));
    }

    /** 执行保单查询任务；当前返回不含真实客户数据的 Mock 说明。 */
    public SubAgentExecutionResult query(String query, String conversationId) {
        Instant answeredAt = Instant.now();
        String answer = "当前为保单查询 Mock 能力，尚未接入客户保单微应用。查询条件：" + query;
        return new SubAgentExecutionResult(
                AGENT_NAME,
                conversationId,
                "pqa-" + UUID.randomUUID().toString().replace("-", ""),
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

    /** 返回保单域隔离的 Skill Hook。 */
    public SkillsAgentHook skillsAgentHook() {
        return skillsAgentHook;
    }

    /** 返回当前注册的保单 Tool 快照；当前阶段为空。 */
    public List<ToolCallback> toolCallbacks() {
        return toolCallbacks;
    }
}
