package com.xxx.insurance.ai.workflow.service;

import com.xxx.insurance.ai.agent.AgentExecutionContext;
import com.xxx.insurance.ai.workflow.model.SubAgentExecutionResult;
import com.xxx.insurance.ai.workflow.model.WorkflowAgentTaskContext;
import com.xxx.insurance.asset.agent.AssetQueryAgent;
import com.xxx.insurance.knowledge.agent.KnowledgeQaAgent;
import com.xxx.insurance.knowledge.model.KnowledgeQaChatRequest;
import com.xxx.insurance.knowledge.model.KnowledgeQaChatResponse;
import com.xxx.insurance.policy.agent.PolicyQueryAgent;
import com.xxx.insurance.product.agent.ProductAnalysisAgent;
import com.xxx.insurance.product.model.ConfirmedProduct;
import com.xxx.insurance.product.model.ProductAnalysisChatRequest;
import com.xxx.insurance.product.model.ProductAnalysisChatResponse;
import org.springframework.stereotype.Service;

/** 将白名单任务类型路由到四个保险业务子智能体。 */
@Service
public class WorkflowSubAgentRouter {

    private final ProductAnalysisAgent productAnalysisAgent;
    private final KnowledgeQaAgent knowledgeQaAgent;
    private final PolicyQueryAgent policyQueryAgent;
    private final AssetQueryAgent assetQueryAgent;

    /** 创建白名单路由器；模型输出不能绕过这里按 Bean 名称任意调用组件。 */
    public WorkflowSubAgentRouter(ProductAnalysisAgent productAnalysisAgent,
                                  KnowledgeQaAgent knowledgeQaAgent,
                                  PolicyQueryAgent policyQueryAgent,
                                  AssetQueryAgent assetQueryAgent) {
        this.productAnalysisAgent = productAnalysisAgent;
        this.knowledgeQaAgent = knowledgeQaAgent;
        this.policyQueryAgent = policyQueryAgent;
        this.assetQueryAgent = assetQueryAgent;
    }

    /** 使用任务最小上下文调用唯一匹配的子智能体。 */
    public SubAgentExecutionResult invoke(WorkflowAgentTaskContext taskContext) {
        String query = buildAgentQuery(taskContext);
        AgentExecutionContext executionContext = new AgentExecutionContext(
                taskContext.workflowInstanceId(),
                taskContext.workflowStepId(),
                taskContext.originalQuestion(),
                false,
                taskContext.task().taskId(),
                taskContext.tokenStreamingEnabled());
        return switch (taskContext.task().agentType()) {
            case ProductAnalysisAgent.AGENT_NAME -> from(productAnalysisAgent.chat(
                    new ProductAnalysisChatRequest(query, taskContext.conversationId()), executionContext));
            case KnowledgeQaAgent.AGENT_NAME -> from(knowledgeQaAgent.chat(
                    new KnowledgeQaChatRequest(query, taskContext.conversationId()), executionContext));
            case PolicyQueryAgent.AGENT_NAME -> policyQueryAgent.query(
                    query, taskContext.conversationId(), executionContext);
            case AssetQueryAgent.AGENT_NAME -> assetQueryAgent.query(
                    query, taskContext.conversationId(), executionContext);
            default -> throw new IllegalArgumentException(
                    "Unsupported workflow agent type: " + taskContext.task().agentType());
        };
    }

    /**
     * 只注入确认产品和明确依赖结果，不携带聊天历史、其他任务结果或完整 Graph State。
     */
    private String buildAgentQuery(WorkflowAgentTaskContext context) {
        StringBuilder query = new StringBuilder(context.task().query());
        if (!context.confirmedProducts().isEmpty()) {
            query.append("\n\n已确认产品：\n");
            context.confirmedProducts().forEach(product -> query.append("- ")
                    .append(formatProduct(product)).append('\n'));
        }
        if (!context.dependencyResults().isEmpty()) {
            query.append("\n明确依赖的上游结果：\n");
            context.dependencyResults().forEach(result -> query.append("- taskId=")
                    .append(result.taskId()).append(", answer=")
                    .append(result.response().answer()).append('\n'));
        }
        return query.toString();
    }

    /** 将标准产品格式化为稳定、最小的 Agent 输入。 */
    private String formatProduct(ConfirmedProduct product) {
        return "%s(%s)".formatted(product.productName(), product.productCode());
    }

    /** 将产品 Agent 响应转换成工作流统一结果。 */
    private SubAgentExecutionResult from(ProductAnalysisChatResponse response) {
        return new SubAgentExecutionResult(
                response.agentName(), response.conversationId(), response.invocationId(), response.answer(),
                response.modelInvoked(), response.durationMs(), response.answeredAt(), response.answerLength(),
                response.memoryEnabled(), response.memoryMessageCount());
    }

    /** 将知识 Agent 响应转换成工作流统一结果。 */
    private SubAgentExecutionResult from(KnowledgeQaChatResponse response) {
        return new SubAgentExecutionResult(
                response.agentName(), response.conversationId(), response.invocationId(), response.answer(),
                response.modelInvoked(), response.durationMs(), response.answeredAt(), response.answerLength(),
                response.memoryEnabled(), response.memoryMessageCount());
    }
}
