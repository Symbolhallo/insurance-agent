package com.xxx.insurance.ai.workflow.execution;

import com.xxx.insurance.ai.agent.AgentExecutionContext;
import com.xxx.insurance.ai.workflow.model.AgentTaskExecutionResult;
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

import java.util.ArrayList;
import java.util.List;

/** 将白名单任务类型路由到四个保险业务子智能体。 */
@Service
public class WorkflowSubAgentRouter {

    /** 四个领域 Agent 当前共同支持的最小输入上限。 */
    private static final int MAX_AGENT_QUERY_LENGTH = 2000;

    private static final String TRUNCATION_MARKER = "...[truncated]";

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
                taskContext.executionFenceToken(),
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
     * 只注入确认产品和明确依赖结果，不携带聊天历史、其他任务结果或完整 Graph State。任务原始问题优先
     * 保留，剩余字符预算由产品信息和各依赖结果公平使用，避免拼接完整上游答案后超过领域 Agent 的输入上限。
     */
    private String buildAgentQuery(WorkflowAgentTaskContext context) {
        StringBuilder query = new StringBuilder(context.task().query());
        int remainingBudget = MAX_AGENT_QUERY_LENGTH - query.length();
        if (remainingBudget <= 0) {
            return query.toString();
        }

        boolean hasDependencyResults = !context.dependencyResults().isEmpty();
        int productBudget = context.confirmedProducts().isEmpty()
                ? 0
                : hasDependencyResults ? remainingBudget / 2 : remainingBudget;
        appendConfirmedProducts(query, context.confirmedProducts(), productBudget);

        int dependencyBudget = MAX_AGENT_QUERY_LENGTH - query.length();
        appendDependencyResults(query, context.dependencyResults(), dependencyBudget);
        return query.toString();
    }

    /** 在分配给产品上下文的预算内，尽量均衡保留每个已确认产品。 */
    private void appendConfirmedProducts(StringBuilder query,
                                         List<ConfirmedProduct> confirmedProducts,
                                         int budget) {
        List<String> entries = new ArrayList<>();
        for (ConfirmedProduct product : confirmedProducts) {
            entries.add(formatProduct(product));
        }
        appendBudgetedSection(query, "\n\n已确认产品：\n", entries, budget);
    }

    /** 在剩余预算内保留每个明确依赖的 taskId，并公平截断过长的上游回答。 */
    private void appendDependencyResults(StringBuilder query,
                                         List<AgentTaskExecutionResult> dependencyResults,
                                         int budget) {
        List<String> entries = new ArrayList<>();
        for (AgentTaskExecutionResult dependencyResult : dependencyResults) {
            String answer = dependencyResult.response() == null
                    ? ""
                    : normalize(dependencyResult.response().answer());
            entries.add("taskId=" + dependencyResult.taskId() + ", answer=" + answer);
        }
        appendBudgetedSection(query, "\n明确依赖的上游结果：\n", entries, budget);
    }

    /** 将预算平均分给尚未写入的条目，保证前序长文本不会独占全部下游输入空间。 */
    private void appendBudgetedSection(StringBuilder query,
                                       String header,
                                       List<String> entries,
                                       int budget) {
        if (entries.isEmpty() || budget <= header.length()) {
            return;
        }

        int sectionEnd = query.length() + budget;
        query.append(header);
        for (int index = 0; index < entries.size(); index++) {
            int remainingCharacters = sectionEnd - query.length();
            int remainingEntries = entries.size() - index;
            int lineBudget = remainingCharacters / remainingEntries;
            appendBudgetedLine(query, entries.get(index), lineBudget);
        }
    }

    /** 写入单个列表项；空间不足时保留前缀并增加明确截断标记。 */
    private void appendBudgetedLine(StringBuilder query, String entry, int lineBudget) {
        String prefix = "- ";
        int contentBudget = lineBudget - prefix.length() - 1;
        if (contentBudget <= 0) {
            return;
        }
        query.append(prefix)
                .append(truncate(entry, contentBudget))
                .append('\n');
    }

    private String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= TRUNCATION_MARKER.length()) {
            return value.substring(0, maxLength);
        }
        return value.substring(0, maxLength - TRUNCATION_MARKER.length()) + TRUNCATION_MARKER;
    }

    /** 压缩上游回答中的连续空白，先减少无业务含义字符，再执行确定性截断。 */
    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
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
