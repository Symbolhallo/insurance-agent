package com.xxx.insurance.ai.workflow.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.xxx.insurance.ai.agent.ReactAgentStreamingExecutor;
import com.xxx.insurance.ai.agent.AgentTokenStreamContext;
import com.xxx.insurance.ai.workflow.model.AgentTaskExecutionResult;
import com.xxx.insurance.ai.workflow.model.AgentTaskStatus;
import com.xxx.insurance.ai.workflow.model.DagExecutionResult;
import com.xxx.insurance.ai.workflow.model.WorkflowSummaryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 将多个子智能体结果合成为统一回答的 Summary Agent 业务门面。
 *
 * <p>单任务成功时直接透传，避免无意义的二次模型调用；存在多个任务结果时才调用独立
 * ReactAgent。该 Agent 不注册 Tool、Skill 或 Memory，只处理本次 DAG 已产生的结果。</p>
 */
public class WorkflowSummaryAgent {

    public static final String AGENT_NAME = "workflow-summary-agent";

    public static final String AGENT_DESCRIPTION = "汇总多个保险业务子智能体结果并披露未完成任务";

    private static final Logger log = LoggerFactory.getLogger(WorkflowSummaryAgent.class);

    private static final String ALL_TASKS_FAILED_ANSWER =
            "本次请求的子智能体任务均未成功完成，请稍后重试或联系人工支持。";

    private final ReactAgent reactAgent;

    private final ReactAgentStreamingExecutor streamingExecutor;

    /** 创建 Summary 业务门面并注入专属 ReactAgent。 */
    public WorkflowSummaryAgent(ReactAgent reactAgent, ReactAgentStreamingExecutor streamingExecutor) {
        this.reactAgent = reactAgent;
        this.streamingExecutor = streamingExecutor;
    }

    /**
     * 根据 DAG 结果选择透传或模型汇总策略。
     *
     * <p>只有一个成功任务且没有失败、跳过任务时直接返回原答案；其余多任务场景由模型
     * 汇总成功结果并明确披露失败或跳过任务。全部任务失败时使用确定性兜底文本。</p>
     */
    public WorkflowSummaryResult summarize(DagExecutionResult dagResult) {
        return summarize(dagResult, false);
    }

    /** 根据 SSE 执行模式选择 ReactAgent.call 或 ReactAgent.stream，业务汇总规则保持一致。 */
    public WorkflowSummaryResult summarize(DagExecutionResult dagResult, boolean tokenStreamingEnabled) {
        return summarize(dagResult, tokenStreamingEnabled, null, null);
    }

    /** 在 SSE 工作流中实时发布 Summary 模型增量内容，完整结果仍返回给后续审核节点。 */
    public WorkflowSummaryResult summarize(DagExecutionResult dagResult,
                                           boolean tokenStreamingEnabled,
                                           String workflowInstanceId,
                                           String conversationId) {
        Instant startedAt = Instant.now();
        List<AgentTaskExecutionResult> successfulTasks = dagResult.taskResults().stream()
                .filter(task -> task.status() == AgentTaskStatus.SUCCESS)
                .toList();
        String summaryId = newSummaryId();

        if (successfulTasks.isEmpty()) {
            return result(summaryId, false, dagResult, 0, ALL_TASKS_FAILED_ANSWER, startedAt);
        }
        if (dagResult.taskResults().size() == 1
                && dagResult.failedCount() == 0
                && dagResult.skippedCount() == 0) {
            return result(summaryId, false, dagResult, 1, successfulTasks.getFirst().response().answer(), startedAt);
        }

        try {
            log.info("[Agent] name={} action=summarize status=start summaryId={} taskCount={} successCount={}",
                    AGENT_NAME, summaryId, dagResult.taskResults().size(), successfulTasks.size());
            String input = buildInput(dagResult);
            AgentTokenStreamContext streamContext = StringUtils.hasText(workflowInstanceId)
                    && StringUtils.hasText(conversationId)
                    ? new AgentTokenStreamContext(
                            workflowInstanceId,
                            conversationId,
                            null,
                            AGENT_NAME,
                            AgentTokenStreamContext.PHASE_SUMMARY)
                    : null;
            String answer = tokenStreamingEnabled
                    ? streamingExecutor.execute(reactAgent, input, streamContext).getText()
                    : reactAgent.call(input).getText();
            if (!StringUtils.hasText(answer)) {
                throw new IllegalStateException("Summary Agent returned blank answer");
            }
            log.info("[Agent] name={} action=summarize status=success summaryId={} answerLength={}",
                    AGENT_NAME, summaryId, answer.length());
            return result(summaryId, true, dagResult, successfulTasks.size(), answer, startedAt);
        }
        catch (Exception ex) {
            log.error("[Agent] name={} action=summarize status=failed summaryId={}", AGENT_NAME, summaryId, ex);
            throw new IllegalStateException("Workflow summary failed", ex);
        }
    }

    /** 返回底层 Summary ReactAgent，供装配验证和测试使用。 */
    public ReactAgent reactAgent() {
        return reactAgent;
    }

    /** 将任务终态与成功答案格式化为仅供 Summary Agent 使用的受控输入。 */
    private String buildInput(DagExecutionResult dagResult) {
        StringBuilder input = new StringBuilder("请汇总以下子智能体任务结果：\n\n");
        for (AgentTaskExecutionResult task : dagResult.taskResults()) {
            input.append("<task_result>\n")
                    .append("taskId: ").append(task.taskId()).append('\n')
                    .append("agentName: ").append(task.agentName()).append('\n')
                    .append("status: ").append(task.status()).append('\n');
            if (task.status() == AgentTaskStatus.SUCCESS) {
                input.append("answer:\n").append(task.response().answer()).append('\n');
            }
            else {
                input.append("error: ").append(task.errorMessage() == null
                        ? task.status().name() : task.errorMessage()).append('\n');
            }
            input.append("</task_result>\n\n");
        }
        return input.toString();
    }

    /** 创建不可变汇总结果并计算节点耗时。 */
    private WorkflowSummaryResult result(String summaryId,
                                         boolean modelInvoked,
                                         DagExecutionResult dagResult,
                                         int successfulTaskCount,
                                         String answer,
                                         Instant startedAt) {
        Instant summarizedAt = Instant.now();
        return new WorkflowSummaryResult(
                summaryId,
                modelInvoked,
                dagResult.taskResults().size(),
                successfulTaskCount,
                answer,
                Duration.between(startedAt, summarizedAt).toMillis(),
                summarizedAt);
    }

    /** 生成一次 Summary 调用编号。 */
    private String newSummaryId() {
        return "wfs-" + UUID.randomUUID().toString().replace("-", "");
    }
}
