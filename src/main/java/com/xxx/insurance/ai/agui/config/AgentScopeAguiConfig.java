package com.xxx.insurance.ai.agui.config;

import com.xxx.insurance.asset.agent.AssetQueryAgent;
import com.xxx.insurance.asset.config.AssetQueryAgentConfig;
import com.xxx.insurance.knowledge.agent.KnowledgeQaAgent;
import com.xxx.insurance.knowledge.config.KnowledgeQaAgentConfig;
import com.xxx.insurance.policy.agent.PolicyQueryAgent;
import com.xxx.insurance.policy.config.PolicyQueryAgentConfig;
import com.xxx.insurance.product.agent.ProductAnalysisAgent;
import com.xxx.insurance.product.config.ProductAnalysisAgentConfig;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.model.ToolMergeMode;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** AgentScope AG-UI 协议适配配置。 */
@Configuration
@EnableConfigurationProperties(AgentScopeAguiProperties.class)
public class AgentScopeAguiConfig {

    /**
     * 注册允许前端直接访问的四个领域 Agent。
     *
     * <p>Planner 和 Summary 属于主工作流内部安全边界，不注册到公共 AG-UI Registry，防止客户端
     * 绕过意图白名单、计划校验和最终审核直接调用内部编排 Agent。</p>
     */
    @Bean
    public AguiAgentRegistry aguiAgentRegistry(
            @Qualifier(ProductAnalysisAgentConfig.PRODUCT_ANALYSIS_REACT_AGENT) HarnessAgent productAgent,
            @Qualifier(KnowledgeQaAgentConfig.KNOWLEDGE_QA_REACT_AGENT) HarnessAgent knowledgeAgent,
            @Qualifier(PolicyQueryAgentConfig.POLICY_QUERY_REACT_AGENT) HarnessAgent policyAgent,
            @Qualifier(AssetQueryAgentConfig.ASSET_QUERY_REACT_AGENT) HarnessAgent assetAgent) {
        AguiAgentRegistry registry = new AguiAgentRegistry();
        registry.register(ProductAnalysisAgent.AGENT_NAME, productAgent);
        registry.register(KnowledgeQaAgent.AGENT_NAME, knowledgeAgent);
        registry.register(PolicyQueryAgent.AGENT_NAME, policyAgent);
        registry.register(AssetQueryAgent.AGENT_NAME, assetAgent);
        return registry;
    }

    /**
     * 配置 AG-UI 事件映射。
     *
     * <p>允许正文、Tool 调用参数、Tool 结果、状态和 Token 用量事件；关闭推理内容。金融场景
     * 禁止客户端注入任意 Tool，只使用服务端已审查并注册到 Toolkit 的领域工具。</p>
     */
    @Bean
    public AguiAdapterConfig aguiAdapterConfig(AgentScopeAguiProperties properties) {
        return AguiAdapterConfig.builder()
                .toolMergeMode(ToolMergeMode.AGENT_ONLY)
                .emitStateEvents(true)
                .emitToolCallArgs(true)
                .emitTokenUsage(true)
                .enableReasoning(false)
                .emitRunFinishedAfterError(false)
                .runTimeout(properties.getRunTimeout())
                .build();
    }

    /** 创建线程安全的 AG-UI JSON/SSE 编码器。 */
    @Bean
    public AguiEventEncoder aguiEventEncoder() {
        return new AguiEventEncoder();
    }
}
