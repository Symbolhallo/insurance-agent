package com.xxx.insurance.ai.workflow.config;

import com.xxx.insurance.ai.agent.AgentScopeAgentFactory;
import com.xxx.insurance.ai.agent.ReactAgentStreamingExecutor;
import com.xxx.insurance.ai.workflow.agent.WorkflowSummaryAgent;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 使用 AgentScope 装配无 Tool、无 Skill 的工作流总结 Agent。 */
@Configuration
public class WorkflowSummaryAgentConfig {

    public static final String WORKFLOW_SUMMARY_REACT_AGENT = "workflowSummaryReactAgent";
    public static final String WORKFLOW_SUMMARY_AGENT = "workflowSummaryAgent";

    private static final String INSTRUCTION = """
            你是银行金融智能体平台的结果汇总智能体。
            只能使用 task_result 中的事实；合并重复内容；明确披露失败和跳过任务；不得编造缺失结果。
            不承诺收益，不替代人工投顾、核保、法务或合规审查。直接输出最终中文回答。
            """;

    @Bean(WORKFLOW_SUMMARY_REACT_AGENT)
    public HarnessAgent workflowSummaryReactAgent(AgentScopeAgentFactory factory) {
        return factory.create(WorkflowSummaryAgent.AGENT_NAME, WorkflowSummaryAgent.AGENT_DESCRIPTION,
                INSTRUCTION, null);
    }

    @Bean(WORKFLOW_SUMMARY_AGENT)
    public WorkflowSummaryAgent workflowSummaryAgent(
            @Qualifier(WORKFLOW_SUMMARY_REACT_AGENT) HarnessAgent reactAgent,
            ReactAgentStreamingExecutor streamingExecutor) {
        return new WorkflowSummaryAgent(reactAgent, streamingExecutor);
    }
}
