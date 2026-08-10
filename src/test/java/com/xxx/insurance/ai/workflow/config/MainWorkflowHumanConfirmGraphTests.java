package com.xxx.insurance.ai.workflow.config;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.serializer.StateSerializer;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.workflow.checkpoint.config.GraphCheckpointConfig;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static org.assertj.core.api.Assertions.assertThat;

class MainWorkflowHumanConfirmGraphTests {

    @Test
    void recallBranchInterruptsAndResumesIntoContextAlignment() throws Exception {
        StateSerializer serializer = new GraphCheckpointConfig()
                .mainWorkflowStateSerializer(new ObjectMapper());
        StateGraph stateGraph = new StateGraph("human-confirm-test", () -> keyStrategies(
                "recallRequired",
                "productCandidates",
                "humanConfirmRequired",
                "resolvedProducts",
                "contextAligned"), serializer)
                .addNode("resolve-product-reference", node_async(state -> Map.of("recallRequired", true)))
                .addNode("retrieve-product-candidates", node_async(state -> Map.of(
                        "productCandidates", List.of("PA-001"),
                        "humanConfirmRequired", true)))
                .addNode("human-confirm-product", node_async(state -> {
                    List<?> resolvedProducts = state.value("resolvedProducts", List.class).orElse(List.of());
                    if (resolvedProducts.isEmpty()) {
                        throw new IllegalStateException("missing confirmed products");
                    }
                    return Map.of("humanConfirmRequired", false);
                }))
                .addNode("context-alignment", node_async(state -> Map.of("contextAligned", true)))
                .addEdge(START, "resolve-product-reference")
                .addConditionalEdges(
                        "resolve-product-reference",
                        edge_async(state -> state.value("recallRequired", Boolean.class).orElse(false)
                                ? "recall" : "skip"),
                        Map.of("recall", "retrieve-product-candidates", "skip", "context-alignment"))
                .addEdge("retrieve-product-candidates", "human-confirm-product")
                .addEdge("human-confirm-product", "context-alignment")
                .addEdge("context-alignment", END);
        CompiledGraph graph = stateGraph.compile(CompileConfig.builder()
                .interruptBefore("human-confirm-product")
                .saverConfig(SaverConfig.builder().register(new MemorySaver()).build())
                .build());
        RunnableConfig config = RunnableConfig.builder().threadId("workflow-001").build();

        NodeOutput interrupted = graph.invokeAndGetOutput(Map.of(), config).orElseThrow();

        assertThat(interrupted.isEND()).isFalse();
        assertThat(interrupted.state().value("productCandidates", List.class))
                .contains(List.of("PA-001"));
        assertThat(interrupted.state().value("contextAligned", Boolean.class)).isEmpty();

        RunnableConfig updated = graph.updateState(
                graph.getState(config).config(),
                Map.of("resolvedProducts", List.of("PA-001"), "humanConfirmRequired", false));
        NodeOutput completed = graph.invokeAndGetOutput(Map.of(), updated.withResume()).orElseThrow();

        assertThat(completed.isEND()).isTrue();
        assertThat(completed.state().value("contextAligned", Boolean.class)).contains(true);
        assertThat(completed.state().value("humanConfirmRequired", Boolean.class)).contains(false);
    }

    private Map<String, KeyStrategy> keyStrategies(String... keys) {
        Map<String, KeyStrategy> strategies = new HashMap<>();
        for (String key : keys) {
            strategies.put(key, new ReplaceStrategy());
        }
        return strategies;
    }
}
