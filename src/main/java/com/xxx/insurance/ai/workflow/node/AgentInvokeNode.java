package com.xxx.insurance.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.xxx.insurance.ai.workflow.model.AgentTaskExecutionResult;
import com.xxx.insurance.ai.workflow.model.AgentTaskStatus;
import com.xxx.insurance.ai.workflow.model.SubAgentExecutionResult;
import com.xxx.insurance.ai.workflow.model.WorkflowAgentTaskContext;
import com.xxx.insurance.ai.workflow.model.WorkflowSseEventType;
import com.xxx.insurance.ai.workflow.model.WorkflowTaskStateKeys;
import com.xxx.insurance.ai.workflow.service.WorkflowEventPublisher;
import com.xxx.insurance.ai.workflow.service.WorkflowSubAgentRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** 单任务子图中唯一执行真实子智能体调用的节点。 */
public class AgentInvokeNode implements AsyncNodeActionWithConfig {

    public static final String TASK_CONTEXT_METADATA = "workflowAgentTaskContext";

    private static final Logger log = LoggerFactory.getLogger(AgentInvokeNode.class);

    private static final String ERROR_AGENT_INVOKE_FAILED = "AGENT_INVOKE_FAILED";

    private final WorkflowSubAgentRouter subAgentRouter;

    private final WorkflowEventPublisher eventPublisher;

    /** 创建任务调用节点，注入白名单路由和 SSE 生命周期发布端口。 */
    public AgentInvokeNode(WorkflowSubAgentRouter subAgentRouter,
                           WorkflowEventPublisher eventPublisher) {
        this.subAgentRouter = subAgentRouter;
        this.eventPublisher = eventPublisher;
    }

    /** 执行有限重试并把普通 Agent 异常收敛为 FAILED，避免终止主 Graph。 */
    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
        WorkflowAgentTaskContext context = config.metadata(TASK_CONTEXT_METADATA)
                .filter(WorkflowAgentTaskContext.class::isInstance)
                .map(WorkflowAgentTaskContext.class::cast)
                .orElseThrow(() -> new IllegalStateException("RunnableConfig has no workflow task context"));
        AgentTaskExecutionResult running = state
                .value(WorkflowTaskStateKeys.TASK_RESULT, AgentTaskExecutionResult.class)
                .orElseThrow(() -> new IllegalStateException("Task graph state has no running result"));
        if (running.terminal()) {
            return CompletableFuture.completedFuture(Map.of(WorkflowTaskStateKeys.TASK_RESULT, running));
        }
        return CompletableFuture.completedFuture(Map.of(
                WorkflowTaskStateKeys.TASK_RESULT, invokeWithRetry(context, running)));
    }

    /** 在同一任务节点内执行受控重试；Checkpoint 只保存 RUNNING 和最终终态。 */
    private AgentTaskExecutionResult invokeWithRetry(WorkflowAgentTaskContext context,
                                                     AgentTaskExecutionResult running) {
        Instant startedAt = running.startedAt() == null ? Instant.now() : running.startedAt();
        publish(context, WorkflowSseEventType.AGENT_START, Map.of(
                "taskId", context.task().taskId(),
                "agentType", context.task().agentType(),
                "status", AgentTaskStatus.RUNNING.name()));

        Exception lastFailure = null;
        int maxAttempts = context.task().maxRetries() + 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                SubAgentExecutionResult response = subAgentRouter.invoke(context);
                Instant endedAt = Instant.now();
                AgentTaskExecutionResult success = new AgentTaskExecutionResult(
                        context.task().taskId(), context.task().sequence(), context.task().agentType(),
                        AgentTaskStatus.SUCCESS, response, null, null, startedAt, endedAt,
                        Duration.between(startedAt, endedAt).toMillis(), attempt);
                publishTerminal(context, success);
                return success;
            }
            catch (Exception ex) {
                lastFailure = ex;
                log.warn("[Workflow] node=agent-invoke action=retry taskId={} agentType={} attempt={} maxAttempts={}",
                        context.task().taskId(), context.task().agentType(), attempt, maxAttempts, ex);
                if (attempt < maxAttempts) {
                    backoff(attempt);
                }
            }
        }

        Instant endedAt = Instant.now();
        AgentTaskExecutionResult failed = new AgentTaskExecutionResult(
                context.task().taskId(), context.task().sequence(), context.task().agentType(),
                AgentTaskStatus.FAILED, null, ERROR_AGENT_INVOKE_FAILED, truncate(lastFailure),
                startedAt, endedAt, Duration.between(startedAt, endedAt).toMillis(), maxAttempts);
        publishTerminal(context, failed);
        return failed;
    }

    /** 使用短指数退避避免瞬时故障时无间隔重放外部请求。 */
    private void backoff(int attempt) {
        try {
            Thread.sleep(Math.min(100L * (1L << (attempt - 1)), 500L));
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Agent retry interrupted", ex);
        }
    }

    /** 发布不含原始客户输入和模型正文的任务终态事件。 */
    private void publishTerminal(WorkflowAgentTaskContext context, AgentTaskExecutionResult result) {
        publish(context, WorkflowSseEventType.AGENT_COMPLETE, Map.of(
                "taskId", result.taskId(),
                "agentType", result.agentName(),
                "status", result.status().name(),
                "attempts", result.attempts(),
                "durationMs", result.durationMs()));
    }

    /** 调用统一 SSE 发布端口；NoOp profile 下不会产生持久化副作用。 */
    private void publish(WorkflowAgentTaskContext context,
                         WorkflowSseEventType type,
                         Map<String, Object> data) {
        eventPublisher.publish(context.workflowInstanceId(), context.conversationId(), type, "agent-invoke", data);
    }

    /** 限制进入 Checkpoint 和审计数据的异常文本长度。 */
    private String truncate(Exception exception) {
        if (exception == null || exception.getMessage() == null) {
            return "Unknown agent invocation failure";
        }
        return exception.getMessage().length() <= 1024
                ? exception.getMessage()
                : exception.getMessage().substring(0, 1024);
    }
}
