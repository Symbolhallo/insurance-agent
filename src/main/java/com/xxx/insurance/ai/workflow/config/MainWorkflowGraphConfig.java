package com.xxx.insurance.ai.workflow.config;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.serializer.StateSerializer;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.xxx.insurance.ai.workflow.checkpoint.config.GraphCheckpointConfig;
import com.xxx.insurance.ai.workflow.model.MainWorkflowStateKeys;
import com.xxx.insurance.ai.workflow.model.ProductRecallDecision;
import com.xxx.insurance.ai.workflow.model.WorkflowNodeDefinition;
import com.xxx.insurance.ai.workflow.lifecycle.MainWorkflowLifecycleListener;
import com.xxx.insurance.ai.workflow.lifecycle.WorkflowNodeExecutionGuard;
import com.xxx.insurance.ai.workflow.node.DagExecutorNode;
import com.xxx.insurance.ai.workflow.node.ContextAlignmentNode;
import com.xxx.insurance.ai.workflow.node.IntentRecognitionNode;
import com.xxx.insurance.ai.workflow.node.HumanConfirmProductNode;
import com.xxx.insurance.ai.workflow.node.PlannerNode;
import com.xxx.insurance.ai.workflow.node.OutputReviewNode;
import com.xxx.insurance.ai.workflow.node.ProductCandidateRetrievalNode;
import com.xxx.insurance.ai.workflow.node.ProductReferenceResolutionNode;
import com.xxx.insurance.ai.workflow.node.SummaryNode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.HashMap;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;

/**
 * 主工作流 Graph 装配。
 *
 * <p>这里开始使用 Spring AI Alibaba Graph 承载 Agent 编排，而不是在普通
 * Service 中直接串行调用。Graph 负责定义节点拓扑和状态流转，具体业务能力仍然
 * 收敛在子智能体内，例如产品分析逻辑继续由 ProductAnalysisAgent 负责。</p>
 *
 * <p>Main Graph v1 的目标是验证未来多智能体平台的主编排入口：
 * START -> ProductReferenceResolution -> (ProductRecall -> HumanConfirm) -> ContextAlignment ->
 * IntentRecognition -> PlannerAgent -> DagExecutor -> Summary -> OutputReview -> END。
 * ProductReferenceResolution 只加载当前会话确认产品并决定候选确认分支；ContextAlignment
 * 在产品实体确定后完成记忆加载、话题对齐和问题改写；Planner 生成受控动态任务图，
 * DagExecutor 依据 dependsOn 和单任务完成事件串行、并行或混合执行。当前产品召回使用 Mock 服务，Human Confirm
 * 已通过 OceanBase Checkpoint 暂停和恢复。</p>
 */
@Configuration
public class MainWorkflowGraphConfig {

    public static final String MAIN_WORKFLOW_GRAPH = "mainWorkflowGraph";

    public static final String MAIN_WORKFLOW_NAME = "main-workflow-v1";

    /**
     * 装配并编译主工作流 StateGraph。
     *
     * <p>各业务 Node 在此注册固定拓扑，所有 NodeAction 先由执行安全门禁包装；编译阶段设置
     * human-confirm-product 前中断，在 local-db profile 下挂载 OceanBase CheckpointSaver 和
     * GraphLifecycleListener。
     * 返回的 CompiledGraph 由 MainWorkflowService 负责首次 invoke、updateState 和 resume。</p>
     *
     * @return 可执行、可中断和可恢复的主工作流 Graph Bean
     */
    @Bean(MAIN_WORKFLOW_GRAPH)
    public CompiledGraph mainWorkflowGraph(ProductReferenceResolutionNode productReferenceResolutionNode,
                                           ProductCandidateRetrievalNode productCandidateRetrievalNode,
                                           HumanConfirmProductNode humanConfirmProductNode,
                                           ContextAlignmentNode contextAlignmentNode,
                                           IntentRecognitionNode intentRecognitionNode,
                                           PlannerNode plannerNode,
                                           DagExecutorNode dagExecutorNode,
                                           OutputReviewNode outputReviewNode,
                                           SummaryNode summaryNode,
                                           WorkflowNodeExecutionGuard workflowNodeExecutionGuard,
                                           ObjectProvider<MainWorkflowLifecycleListener> lifecycleListenerProvider,
                                           @Qualifier(GraphCheckpointConfig.MAIN_WORKFLOW_STATE_SERIALIZER)
                                           StateSerializer stateSerializer,
                                           @Qualifier(GraphCheckpointConfig.MAIN_WORKFLOW_CHECKPOINT_SAVER)
                                           ObjectProvider<BaseCheckpointSaver> checkpointSaverProvider)
            throws GraphStateException {
        StateGraph stateGraph = new StateGraph(MAIN_WORKFLOW_NAME, this::mainWorkflowKeyStrategies, stateSerializer)
                .addNode(WorkflowNodeDefinition.PRODUCT_REFERENCE_RESOLUTION.code(),
                        node_async(tracked(WorkflowNodeDefinition.PRODUCT_REFERENCE_RESOLUTION,
                                productReferenceResolutionNode, workflowNodeExecutionGuard)))
                .addNode(WorkflowNodeDefinition.PRODUCT_CANDIDATE_RETRIEVAL.code(),
                        node_async(tracked(WorkflowNodeDefinition.PRODUCT_CANDIDATE_RETRIEVAL,
                                productCandidateRetrievalNode, workflowNodeExecutionGuard)))
                .addNode(WorkflowNodeDefinition.HUMAN_CONFIRM_PRODUCT.code(),
                        node_async(tracked(WorkflowNodeDefinition.HUMAN_CONFIRM_PRODUCT,
                                humanConfirmProductNode, workflowNodeExecutionGuard)))
                .addNode(WorkflowNodeDefinition.CONTEXT_ALIGNMENT.code(),
                        node_async(tracked(WorkflowNodeDefinition.CONTEXT_ALIGNMENT, contextAlignmentNode,
                                workflowNodeExecutionGuard)))
                .addNode(WorkflowNodeDefinition.INTENT_RECOGNITION.code(),
                        node_async(tracked(WorkflowNodeDefinition.INTENT_RECOGNITION, intentRecognitionNode,
                                workflowNodeExecutionGuard)))
                .addNode(WorkflowNodeDefinition.PLANNER.code(),
                        node_async(tracked(WorkflowNodeDefinition.PLANNER, plannerNode,
                                workflowNodeExecutionGuard)))
                .addNode(WorkflowNodeDefinition.DAG_EXECUTOR.code(),
                        node_async(tracked(WorkflowNodeDefinition.DAG_EXECUTOR, dagExecutorNode,
                                workflowNodeExecutionGuard)))
                .addNode(WorkflowNodeDefinition.OUTPUT_REVIEW.code(),
                        node_async(tracked(WorkflowNodeDefinition.OUTPUT_REVIEW, outputReviewNode,
                                workflowNodeExecutionGuard)))
                .addNode(WorkflowNodeDefinition.SUMMARY.code(),
                        node_async(tracked(WorkflowNodeDefinition.SUMMARY, summaryNode,
                                workflowNodeExecutionGuard)))
                .addEdge(START, WorkflowNodeDefinition.PRODUCT_REFERENCE_RESOLUTION.code())
                .addConditionalEdges(
                        WorkflowNodeDefinition.PRODUCT_REFERENCE_RESOLUTION.code(),
                        edge_async(state -> state
                                .value(MainWorkflowStateKeys.PRODUCT_RECALL_DECISION, ProductRecallDecision.class)
                                .map(ProductRecallDecision::required)
                                .orElseThrow(() -> new IllegalStateException("Missing product recall decision"))
                                ? "recall" : "skip"),
                        Map.of(
                                "recall", WorkflowNodeDefinition.PRODUCT_CANDIDATE_RETRIEVAL.code(),
                                "skip", WorkflowNodeDefinition.CONTEXT_ALIGNMENT.code()))
                .addEdge(WorkflowNodeDefinition.PRODUCT_CANDIDATE_RETRIEVAL.code(),
                        WorkflowNodeDefinition.HUMAN_CONFIRM_PRODUCT.code())
                .addEdge(WorkflowNodeDefinition.HUMAN_CONFIRM_PRODUCT.code(),
                        WorkflowNodeDefinition.CONTEXT_ALIGNMENT.code())
                .addEdge(WorkflowNodeDefinition.CONTEXT_ALIGNMENT.code(),
                        WorkflowNodeDefinition.INTENT_RECOGNITION.code())
                .addEdge(WorkflowNodeDefinition.INTENT_RECOGNITION.code(), WorkflowNodeDefinition.PLANNER.code())
                .addEdge(WorkflowNodeDefinition.PLANNER.code(), WorkflowNodeDefinition.DAG_EXECUTOR.code())
                .addEdge(WorkflowNodeDefinition.DAG_EXECUTOR.code(), WorkflowNodeDefinition.SUMMARY.code())
                .addEdge(WorkflowNodeDefinition.SUMMARY.code(), WorkflowNodeDefinition.OUTPUT_REVIEW.code())
                .addEdge(WorkflowNodeDefinition.OUTPUT_REVIEW.code(), END);

        BaseCheckpointSaver checkpointSaver = checkpointSaverProvider.getIfAvailable();
        CompileConfig.Builder compileConfigBuilder = CompileConfig.builder()
                .interruptBefore(WorkflowNodeDefinition.HUMAN_CONFIRM_PRODUCT.code())
                .releaseThread(false);
        lifecycleListenerProvider.ifAvailable(compileConfigBuilder::withLifecycleListener);
        if (checkpointSaver != null) {
            compileConfigBuilder.saverConfig(SaverConfig.builder().register(checkpointSaver).build());
        }
        return stateGraph.compile(compileConfigBuilder.build());
    }

    /**
     * 为 Main Graph 的全部状态键注册覆盖策略。
     *
     * <p>当前节点均写入完整业务快照，因此统一使用 ReplaceStrategy，避免恢复时产生未定义状态键。</p>
     */
    private Map<String, KeyStrategy> mainWorkflowKeyStrategies() {
        Map<String, KeyStrategy> strategies = new HashMap<>();
        MainWorkflowStateKeys.all().forEach(key -> strategies.put(key, new ReplaceStrategy()));
        return strategies;
    }

    /**
     * 使用统一安全门禁包装节点，强制步骤状态、Lease/Fence 与结果审计。
     */
    private NodeAction tracked(WorkflowNodeDefinition nodeDefinition,
                               NodeAction nodeAction,
                               WorkflowNodeExecutionGuard workflowNodeExecutionGuard) {
        return state -> workflowNodeExecutionGuard.execute(nodeDefinition, state, () -> nodeAction.apply(state));
    }

    /**
     * 从 Graph State 读取节点编码到数据库步骤编号的映射。
     *
     * @param state 当前 Graph 状态
     * @return 步骤编号映射；状态中不存在时返回空 Map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, String> workflowStepIds(OverAllState state) {
        return state.value(MainWorkflowStateKeys.WORKFLOW_STEP_IDS, Map.class)
                .map(value -> (Map<String, String>) value)
                .orElse(Map.of());
    }
}
