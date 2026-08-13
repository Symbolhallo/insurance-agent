package com.xxx.insurance.ai.workflow.config;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.serializer.StateSerializer;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.xxx.insurance.ai.workflow.checkpoint.config.GraphCheckpointConfig;
import com.xxx.insurance.ai.workflow.model.WorkflowTaskStateKeys;
import com.xxx.insurance.ai.workflow.node.AgentInvokeNode;
import com.xxx.insurance.ai.workflow.node.TaskMarkRunningNode;
import com.xxx.insurance.ai.workflow.sse.service.WorkflowEventPublisher;
import com.xxx.insurance.ai.workflow.execution.WorkflowSubAgentRouter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/** 装配 Planner 单任务的可恢复子图。 */
@Configuration
public class WorkflowTaskGraphConfig {

    public static final String WORKFLOW_TASK_GRAPH = "workflowTaskGraph";

    public static final String TASK_MARK_RUNNING = "task-mark-running";

    public static final String AGENT_INVOKE = "agent-invoke";

    /** 创建任务调用节点 Bean，使白名单路由和 SSE 端口保持统一。 */
    @Bean
    public AgentInvokeNode agentInvokeNode(WorkflowSubAgentRouter subAgentRouter,
                                           WorkflowEventPublisher eventPublisher) {
        return new AgentInvokeNode(subAgentRouter, eventPublisher);
    }

    /**
     * 编译 `mark-running -> agent-invoke` 子图，并复用主图 StateSerializer 与 OceanBase Saver。
     */
    @Bean(WORKFLOW_TASK_GRAPH)
    public CompiledGraph workflowTaskGraph(
            AgentInvokeNode agentInvokeNode,
            @Qualifier(GraphCheckpointConfig.MAIN_WORKFLOW_STATE_SERIALIZER) StateSerializer stateSerializer,
            @Qualifier(GraphCheckpointConfig.MAIN_WORKFLOW_CHECKPOINT_SAVER)
            ObjectProvider<BaseCheckpointSaver> checkpointSaverProvider) throws GraphStateException {
        Map<String, KeyStrategy> strategies = Map.of(
                WorkflowTaskStateKeys.TASK_RESULT, new ReplaceStrategy());
        StateGraph taskGraph = new StateGraph("workflow-agent-task-v1", () -> strategies, stateSerializer)
                .addNode(TASK_MARK_RUNNING, node_async(new TaskMarkRunningNode()))
                .addNode(AGENT_INVOKE, agentInvokeNode)
                .addEdge(START, TASK_MARK_RUNNING)
                .addEdge(TASK_MARK_RUNNING, AGENT_INVOKE)
                .addEdge(AGENT_INVOKE, END);

        CompileConfig.Builder compileConfig = CompileConfig.builder().releaseThread(false);
        BaseCheckpointSaver saver = checkpointSaverProvider.getIfAvailable();
        if (saver != null) {
            compileConfig.saverConfig(SaverConfig.builder().register(saver).build());
        }
        return taskGraph.compile(compileConfig.build());
    }
}
