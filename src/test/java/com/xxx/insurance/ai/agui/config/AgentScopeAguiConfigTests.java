package com.xxx.insurance.ai.agui.config;

import com.xxx.insurance.asset.agent.AssetQueryAgent;
import com.xxx.insurance.knowledge.agent.KnowledgeQaAgent;
import com.xxx.insurance.policy.agent.PolicyQueryAgent;
import com.xxx.insurance.product.agent.ProductAnalysisAgent;
import io.agentscope.core.agui.model.ToolMergeMode;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentScopeAguiConfigTests {

    private final AgentScopeAguiConfig config = new AgentScopeAguiConfig();

    @Test
    void exposesOnlyFourDomainAgents() {
        var registry = config.aguiAgentRegistry(
                mock(HarnessAgent.class), mock(HarnessAgent.class),
                mock(HarnessAgent.class), mock(HarnessAgent.class));

        assertThat(registry.size()).isEqualTo(4);
        assertThat(registry.hasAgent(ProductAnalysisAgent.AGENT_NAME)).isTrue();
        assertThat(registry.hasAgent(KnowledgeQaAgent.AGENT_NAME)).isTrue();
        assertThat(registry.hasAgent(PolicyQueryAgent.AGENT_NAME)).isTrue();
        assertThat(registry.hasAgent(AssetQueryAgent.AGENT_NAME)).isTrue();
        assertThat(registry.hasAgent("workflow-planner-agent")).isFalse();
        assertThat(registry.hasAgent("workflow-summary-agent")).isFalse();
    }

    @Test
    void rejectsFrontendToolInjectionAndReasoningDisclosure() {
        AgentScopeAguiProperties properties = new AgentScopeAguiProperties();
        properties.setRunTimeout(Duration.ofMinutes(3));
        var adapterConfig = config.aguiAdapterConfig(properties);

        assertThat(adapterConfig.getToolMergeMode()).isEqualTo(ToolMergeMode.AGENT_ONLY);
        assertThat(adapterConfig.isEmitStateEvents()).isTrue();
        assertThat(adapterConfig.isEmitToolCallArgs()).isTrue();
        assertThat(adapterConfig.isEmitTokenUsage()).isTrue();
        assertThat(adapterConfig.isEnableReasoning()).isFalse();
        assertThat(adapterConfig.getRunTimeout()).isEqualTo(Duration.ofMinutes(3));
    }
}
