package com.xxx.insurance.ai.workflow.agent;

import io.agentscope.harness.agent.HarnessAgent;
import com.xxx.insurance.ai.agent.AgentTokenStreamContext;
import com.xxx.insurance.ai.agent.AgentScopeStructuredOutput;
import com.xxx.insurance.ai.agent.ReactAgentStreamingExecutor;
import com.xxx.insurance.ai.workflow.model.AlignedWorkflowContext;
import com.xxx.insurance.ai.workflow.model.IntentRoutingResult;
import com.xxx.insurance.ai.workflow.model.IntentRoute;
import com.xxx.insurance.ai.workflow.model.WorkflowPlan;
import com.xxx.insurance.ai.workflow.execution.WorkflowPlanValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 主工作流 Planner Agent 的业务入口。
 *
 * <p>Planner 只把已识别的意图转换为结构化执行计划，不执行产品查询，也不直接调用
 * 业务 Tool。模型输出必须先经过 JSON 转换和白名单校验，随后才能写入 Graph state。</p>
 */
public class WorkflowPlannerAgent {

    public static final String AGENT_NAME = "workflow-planner-agent";

    public static final String AGENT_DESCRIPTION = "将金融业务意图转换为受控智能体执行计划";

    private static final Logger log = LoggerFactory.getLogger(WorkflowPlannerAgent.class);

    private final HarnessAgent reactAgent;

    private final AgentScopeStructuredOutput structuredOutput;

    private final WorkflowPlanValidator workflowPlanValidator;

    private final ReactAgentStreamingExecutor streamingExecutor;

    /**
     * 创建 Planner 业务门面，组合模型执行、结构化转换和本地安全校验。
     */
    public WorkflowPlannerAgent(HarnessAgent reactAgent,
                                AgentScopeStructuredOutput structuredOutput,
                                WorkflowPlanValidator workflowPlanValidator,
                                ReactAgentStreamingExecutor streamingExecutor) {
        this.reactAgent = reactAgent;
        this.structuredOutput = structuredOutput;
        this.workflowPlanValidator = workflowPlanValidator;
        this.streamingExecutor = streamingExecutor;
    }

    /**
     * 调用独立 Planner HarnessAgent，把受控意图路由转换为结构化 DAG 计划。
     *
     * <p>HarnessAgent 只负责生成符合结构合同的 JSON；模型结果必须经过
     * {@link WorkflowPlanValidator} 的任务数量、Agent 白名单和依赖方向校验后，才能写入 Graph State。</p>
     */
    public WorkflowPlan plan(AlignedWorkflowContext context,
                             IntentRoutingResult routingResult) {
        return plan(context, routingResult, null);
    }

    /** 在 SSE 模式下实时发布 Planner HarnessAgent 的结构化计划 Token。 */
    public WorkflowPlan plan(AlignedWorkflowContext context,
                             IntentRoutingResult routingResult,
                             AgentTokenStreamContext streamContext) {
        String plannerInput = """
                请为下面的请求生成执行计划。

                已识别意图及允许调用的智能体：
                %s
                当前会话窗口消息数：%d
                当前长期记忆条数：%d

                输出必须满足以下格式：
                %s

                <user_request>
                %s
                </user_request>
                """.formatted(
                formatRoutes(routingResult),
                context.chatMessageCount(),
                context.longTermMemoryCount(),
                structuredOutput.schema(WorkflowPlan.class),
                context.rewrittenQuestion());
        try {
            log.info("[Agent] name={} action=plan status=start conversationId={} intent={}",
                    AGENT_NAME,
                    context.conversationId(),
                    routingResult.intent());
            String modelOutput = streamingExecutor.execute(reactAgent, plannerInput, streamContext).getTextContent();
            WorkflowPlan plan = workflowPlanValidator.validate(
                    structuredOutput.convert(modelOutput, WorkflowPlan.class), routingResult);
            log.info("[Agent] name={} action=plan status=success conversationId={} taskCount={} targetAgents={}",
                    AGENT_NAME,
                    context.conversationId(),
                    plan.tasks().size(),
                    plan.tasks().stream().map(task -> task.agentName()).toList());
            return plan;
        }
        catch (Exception ex) {
            log.error("[Agent] name={} action=plan status=failed conversationId={}",
                    AGENT_NAME,
                    context.conversationId(),
                    ex);
            throw new IllegalStateException("Workflow planning failed", ex);
        }
    }

    /**
     * 返回底层 Planner HarnessAgent，主要供装配验证和测试使用。
     */
    public HarnessAgent reactAgent() {
        return reactAgent;
    }

    /**
     * 将全部意图路由格式化为 Planner 可读取的受控输入段落。
     */
    private String formatRoutes(IntentRoutingResult routingResult) {
        return routingResult.routes().stream()
                .map(this::formatRoute)
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow(() -> new IllegalArgumentException("Intent routes must not be empty"));
    }

    /**
     * 将单个意图、允许 Agent 和独立问题格式化为一行 Planner 输入。
     */
    private String formatRoute(IntentRoute route) {
        return "- intent=%s, allowedAgent=%s, intentionQuery=%s".formatted(
                route.intent(),
                route.targetAgent(),
                route.intentionQuery());
    }
}
