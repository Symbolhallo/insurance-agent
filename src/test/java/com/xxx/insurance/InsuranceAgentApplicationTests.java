package com.xxx.insurance;

import com.xxx.insurance.ai.config.SkillConfig;
import com.xxx.insurance.asset.config.AssetQueryAgentConfig;
import com.xxx.insurance.knowledge.config.KnowledgeQaAgentConfig;
import com.xxx.insurance.policy.config.PolicyQueryAgentConfig;
import com.xxx.insurance.product.config.ProductAnalysisAgentConfig;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "insurance.ai.model.api-key=test-key",
        "insurance.ai.model.base-url=https://api.deepseek.com",
        "insurance.ai.model.model-name=deepseek-chat"
})
class InsuranceAgentApplicationTests {

    private final Model model;
    private final HarnessAgent productAgent;
    private final HarnessAgent knowledgeAgent;
    private final HarnessAgent policyAgent;
    private final HarnessAgent assetAgent;
    private final AgentSkillRepository productSkills;
    private final AgentSkillRepository knowledgeSkills;

    @Autowired
    InsuranceAgentApplicationTests(
            Model model,
            @Qualifier(ProductAnalysisAgentConfig.PRODUCT_ANALYSIS_REACT_AGENT) HarnessAgent productAgent,
            @Qualifier(KnowledgeQaAgentConfig.KNOWLEDGE_QA_REACT_AGENT) HarnessAgent knowledgeAgent,
            @Qualifier(PolicyQueryAgentConfig.POLICY_QUERY_REACT_AGENT) HarnessAgent policyAgent,
            @Qualifier(AssetQueryAgentConfig.ASSET_QUERY_REACT_AGENT) HarnessAgent assetAgent,
            @Qualifier(SkillConfig.PRODUCT_ANALYSIS_SKILL_REPOSITORY) AgentSkillRepository productSkills,
            @Qualifier(SkillConfig.KNOWLEDGE_QA_SKILL_REPOSITORY) AgentSkillRepository knowledgeSkills) {
        this.model = model;
        this.productAgent = productAgent;
        this.knowledgeAgent = knowledgeAgent;
        this.policyAgent = policyAgent;
        this.assetAgent = assetAgent;
        this.productSkills = productSkills;
        this.knowledgeSkills = knowledgeSkills;
    }

    @Test
    void contextLoadsAgentScopeRuntimeAndIsolatedAgents() {
        assertThat(model).isNotNull();
        assertThat(productAgent.getName()).isEqualTo("product-analysis-agent");
        assertThat(knowledgeAgent.getName()).isEqualTo("knowledge-qa-agent");
        assertThat(policyAgent.getName()).isEqualTo("policy-query-agent");
        assertThat(assetAgent.getName()).isEqualTo("asset-query-agent");
        assertThat(productSkills.getAllSkillNames())
                .containsExactlyInAnyOrder("limited-product-analysis", "batch-product-analysis");
        assertThat(knowledgeSkills.getAllSkillNames()).containsExactly("insurance-business-qa");
    }
}
