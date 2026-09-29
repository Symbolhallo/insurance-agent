package com.xxx.insurance.ai.workflow.config;

import com.xxx.insurance.ai.agent.AgentScopeAgentFactory;
import com.xxx.insurance.ai.agent.AgentScopeStructuredOutput;
import com.xxx.insurance.ai.agent.ReactAgentStreamingExecutor;
import com.xxx.insurance.ai.workflow.agent.WorkflowPlannerAgent;
import com.xxx.insurance.ai.workflow.execution.WorkflowPlanValidator;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 使用 AgentScope 装配动态 DAG Planner。 */
@Configuration
public class WorkflowPlannerAgentConfig {

    public static final String WORKFLOW_PLANNER_REACT_AGENT = "workflowPlannerReactAgent";
    public static final String WORKFLOW_PLANNER_AGENT = "workflowPlannerAgent";

    private static final String INSTRUCTION = """
            你是银行金融智能体平台的工作流规划智能体，只生成结构化执行计划，不回答问题或调用业务工具。
            任务数为1到12；taskId唯一；sequence从1连续递增；agentType只能使用输入允许的智能体。
            dependsOn只表达真实前置依赖，禁止自依赖和环；maxRetries为0到3；query保留用户目标且不添加事实。
            只输出符合用户消息中 JSON Schema 的 JSON，不输出 Markdown、解释或内部思维过程。
            输出根对象必须直接包含 objective、tasks、rationale；禁止输出 type、properties 等 JSON Schema 元数据。
            """;

    @Bean(WORKFLOW_PLANNER_REACT_AGENT)
    public HarnessAgent workflowPlannerReactAgent(AgentScopeAgentFactory factory) {
        return factory.create(WorkflowPlannerAgent.AGENT_NAME, WorkflowPlannerAgent.AGENT_DESCRIPTION,
                INSTRUCTION, null);
    }

    @Bean(WORKFLOW_PLANNER_AGENT)
    public WorkflowPlannerAgent workflowPlannerAgent(
            @Qualifier(WORKFLOW_PLANNER_REACT_AGENT) HarnessAgent reactAgent,
            AgentScopeStructuredOutput structuredOutput,
            WorkflowPlanValidator validator,
            ReactAgentStreamingExecutor streamingExecutor) {
        return new WorkflowPlannerAgent(reactAgent, structuredOutput, validator, streamingExecutor);
    }
}
