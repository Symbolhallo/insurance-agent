package com.xxx.insurance.ai.workflow.execution;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.xxx.insurance.ai.workflow.checkpoint.OceanBaseCheckpointSaver;
import com.xxx.insurance.ai.workflow.config.WorkflowExecutionConfig;
import com.xxx.insurance.ai.workflow.config.WorkflowLifecycleProperties;
import com.xxx.insurance.ai.workflow.config.WorkflowTaskGraphConfig;
import com.xxx.insurance.ai.workflow.model.AgentTaskExecutionResult;
import com.xxx.insurance.ai.workflow.model.AgentTaskStatus;
import com.xxx.insurance.ai.workflow.model.WorkflowAgentTaskContext;
import com.xxx.insurance.ai.workflow.model.WorkflowTaskStateKeys;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import static com.xxx.insurance.ai.workflow.node.AgentInvokeNode.TASK_CONTEXT_METADATA;

/** 执行并恢复单任务子图，确保 SUCCESS 任务不会重复调用子智能体。 */
@Service
public class WorkflowTaskGraphRunner {

    private final CompiledGraph taskGraph;

    private final ThreadPoolTaskExecutor taskExecutor;

    private final WorkflowLifecycleProperties lifecycleProperties;

    /** 创建任务图运行器，并绑定项目自己的有界并行 Executor。 */
    public WorkflowTaskGraphRunner(
            @Qualifier(WorkflowTaskGraphConfig.WORKFLOW_TASK_GRAPH) CompiledGraph taskGraph,
            @Qualifier(WorkflowExecutionConfig.WORKFLOW_DAG_TASK_EXECUTOR)
            ThreadPoolTaskExecutor taskExecutor,
            WorkflowLifecycleProperties lifecycleProperties) {
        this.taskGraph = taskGraph;
        this.taskExecutor = taskExecutor;
        this.lifecycleProperties = lifecycleProperties;
    }

    /**
     * 优先读取任务 thread 最新终态；只有不存在终态时才启动或恢复子图。
     */
    public AgentTaskExecutionResult execute(WorkflowAgentTaskContext context) {
        RunnableConfig config = runnableConfig(context);
        AgentTaskExecutionResult recoveredResult = recoveredResult(config);
        if (recoveredResult != null && recoveredResult.terminal()) {
            return recoveredResult;
        }

        Map<String, Object> graphInput = recoveredResult == null
                ? Map.of(WorkflowTaskStateKeys.TASK_RESULT, pending(context))
                : Map.of();
        NodeOutput graphOutput = taskGraph.invokeAndGetOutput(graphInput, config)
                .orElseThrow(() -> new IllegalStateException("Task graph returned empty output"));
        if (!graphOutput.isEND()) {
            throw new IllegalStateException("Task graph stopped before END");
        }
        AgentTaskExecutionResult taskResult = graphOutput.state()
                .value(WorkflowTaskStateKeys.TASK_RESULT, AgentTaskExecutionResult.class)
                .orElseThrow(() -> new IllegalStateException("Task graph returned no task result"));
        if (!taskResult.terminal()) {
            throw new IllegalStateException("Task graph returned non-terminal task result: " + taskResult.status());
        }
        return taskResult;
    }

    /** 读取任务子图最新 Checkpoint 中的结果；首次执行或旧快照无结果时返回 null。 */
    private AgentTaskExecutionResult recoveredResult(RunnableConfig config) {
        StateSnapshot snapshot = taskGraph.stateOf(config).orElse(null);
        if (snapshot == null) {
            return null;
        }
        return snapshot.state()
                .value(WorkflowTaskStateKeys.TASK_RESULT, AgentTaskExecutionResult.class)
                .orElse(null);
    }

    /** 创建任务专属 threadId，并通过 RunnableConfig 提供最小上下文和受控 Executor。 */
    private RunnableConfig runnableConfig(WorkflowAgentTaskContext context) {
        return RunnableConfig.builder()
                .threadId(taskThreadId(context.workflowInstanceId(), context.task().taskId()))
                .addMetadata(TASK_CONTEXT_METADATA, context)
                .addMetadata(OceanBaseCheckpointSaver.METADATA_WORKFLOW_INSTANCE_ID, context.workflowInstanceId())
                .addMetadata(OceanBaseCheckpointSaver.METADATA_CONVERSATION_ID, context.conversationId())
                .addMetadata(OceanBaseCheckpointSaver.METADATA_EXECUTION_OWNER,
                        lifecycleProperties.getInstanceId())
                .addMetadata(OceanBaseCheckpointSaver.METADATA_EXECUTION_FENCE_TOKEN,
                        context.executionFenceToken())
                .defaultParallelExecutor(taskExecutor)
                .build();
    }

    /** 构造首次执行输入；READY 表示依赖已满足且已被调度器选中。 */
    private AgentTaskExecutionResult pending(WorkflowAgentTaskContext context) {
        return new AgentTaskExecutionResult(
                context.task().taskId(), context.task().sequence(), context.task().agentType(),
                AgentTaskStatus.READY, null, null, null, null, null, 0, 0);
    }

    /** 生成与主工作流隔离且可确定重建的任务 Checkpoint threadId。 */
    static String taskThreadId(String workflowInstanceId, String taskId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(taskId.getBytes(StandardCharsets.UTF_8));
            return workflowInstanceId + ":t:" + HexFormat.of().formatHex(digest, 0, 12);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
