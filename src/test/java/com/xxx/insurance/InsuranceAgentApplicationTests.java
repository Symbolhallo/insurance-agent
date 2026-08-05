package com.xxx.insurance;

import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.xxx.insurance.ai.config.SkillConfig;
import com.xxx.insurance.product.agent.ProductAnalysisAgent;
import com.xxx.insurance.product.config.ProductAnalysisAgentConfig;
import com.xxx.insurance.product.model.ProductAnalysisRequest;
import com.xxx.insurance.product.model.ProductAnalysisResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.ai.openai.api-key=test-api-key")
class InsuranceAgentApplicationTests {

    @Autowired
    @Qualifier(SkillConfig.PRODUCT_ANALYSIS_SKILL_REGISTRY)
    private SkillRegistry productAnalysisSkillRegistry;

    @Autowired
    @Qualifier(SkillConfig.PRODUCT_ANALYSIS_SKILLS_AGENT_HOOK)
    private SkillsAgentHook productAnalysisSkillsAgentHook;

    @Autowired
    @Qualifier(ProductAnalysisAgentConfig.PRODUCT_ANALYSIS_AGENT)
    private ProductAnalysisAgent productAnalysisAgent;

    @Test
    void contextLoads() {
    }

    @Test
    void productAnalysisSkillRegistryLoadsOnlyProductAnalysisSkills() {
        assertThat(productAnalysisSkillRegistry.size()).isEqualTo(2);
        assertThat(productAnalysisSkillRegistry.contains("limited-product-analysis")).isTrue();
        assertThat(productAnalysisSkillRegistry.contains("batch-product-analysis")).isTrue();
        assertThat(productAnalysisSkillRegistry.listAll())
                .extracting(skill -> skill.getSkillPath().replace('\\', '/'))
                .allMatch(skillPath -> skillPath.contains("skills/product-analysis"));
    }

    @Test
    void productAnalysisSkillsAgentHookUsesProductAnalysisRegistry() {
        assertThat(productAnalysisSkillsAgentHook.getSkillRegistry()).isSameAs(productAnalysisSkillRegistry);
        assertThat(productAnalysisSkillsAgentHook.getSkillCount()).isEqualTo(2);
        assertThat(productAnalysisSkillsAgentHook.hasSkill("limited-product-analysis")).isTrue();
        assertThat(productAnalysisSkillsAgentHook.hasSkill("batch-product-analysis")).isTrue();
    }

    @Test
    void productAnalysisAgentAssemblesReactAgentWithProductSkills() {
        assertThat(productAnalysisAgent.name()).isEqualTo(ProductAnalysisAgent.AGENT_NAME);
        assertThat(productAnalysisAgent.description()).isEqualTo(ProductAnalysisAgent.AGENT_DESCRIPTION);
        assertThat(productAnalysisAgent.reactAgent()).isNotNull();
        assertThat(productAnalysisAgent.skillsAgentHook()).isSameAs(productAnalysisSkillsAgentHook);
        assertThat(productAnalysisAgent.skillsAgentHook().getSkillCount()).isEqualTo(2);
    }

    @Test
    void productAnalysisAgentReturnsFormattedMockAnalysisResult() {
        ProductAnalysisRequest request = new ProductAnalysisRequest(
                List.of("PA-001", "PA-002", "UNKNOWN"),
                "35岁家庭经济支柱，关注保障责任和长期规划",
                List.of("coverage", "risk"));

        ProductAnalysisResult result = productAnalysisAgent.analyze(request);

        assertThat(result.summary()).contains("2 个保险产品");
        assertThat(result.productItems())
                .extracting(ProductAnalysisResult.ProductAnalysisItem::productCode)
                .containsExactly("PA-001", "PA-002");
        assertThat(result.missingProductCodes()).containsExactly("UNKNOWN");
        assertThat(result.complianceNotes())
                .anyMatch(note -> note.contains("不构成保险销售、投资建议或承诺收益"));
    }

    @Test
    void productAnalysisAgentRejectsEmptyProductCodes() {
        ProductAnalysisRequest request = new ProductAnalysisRequest(
                List.of(" ", ""),
                "客户需求",
                List.of("coverage"));

        assertThatThrownBy(() -> productAnalysisAgent.analyze(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one product code is required");
    }
}
