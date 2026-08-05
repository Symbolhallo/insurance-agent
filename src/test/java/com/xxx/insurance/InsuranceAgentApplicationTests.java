package com.xxx.insurance;

import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.xxx.insurance.ai.config.SkillConfig;
import com.xxx.insurance.product.agent.ProductAnalysisAgent;
import com.xxx.insurance.product.config.ProductAnalysisAgentConfig;
import com.xxx.insurance.product.model.ProductAnalysisRequest;
import com.xxx.insurance.product.model.ProductAnalysisResult;
import com.xxx.insurance.product.tool.ProductAnalysisTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.ai.tool.ToolCallback;

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

    @Autowired
    private ProductAnalysisTool productAnalysisTool;

    @Autowired
    @Qualifier("productAnalysisToolCallbacks")
    private List<ToolCallback> productAnalysisToolCallbacks;

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
        assertThat(productAnalysisSkillRegistry.get("limited-product-analysis")).isPresent()
                .get()
                .extracting(skill -> skill.getAllowedTools())
                .isEqualTo(List.of(ProductAnalysisTool.TOOL_NAME));
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

    @Test
    void productAnalysisToolCallbackIsRegisteredForReactAgent() {
        assertThat(productAnalysisToolCallbacks).hasSize(1);
        ToolCallback toolCallback = productAnalysisToolCallbacks.getFirst();
        assertThat(toolCallback.getToolDefinition().name()).isEqualTo(ProductAnalysisTool.TOOL_NAME);
        assertThat(toolCallback.getToolDefinition().description()).contains("Mock数据");
        assertThat(toolCallback.getToolDefinition().inputSchema()).contains("productCodes");
    }

    @Test
    void productAnalysisToolCallbackCanExecuteWithJsonInput() {
        String toolResult = productAnalysisToolCallbacks.getFirst().call("""
                {
                  "productCodes": ["PA-001"],
                  "customerProfile": "客户关注长期保障",
                  "analysisDimensions": ["coverage", "risk"]
                }
                """);

        assertThat(toolResult).contains("PA-001");
        assertThat(toolResult).contains("安享一生终身寿险");
        assertThat(toolResult).contains("MockProductAnalysisService");
    }

    @Test
    void productAnalysisToolReturnsStructuredResult() {
        ProductAnalysisResult result = productAnalysisTool.analyzeProducts(
                List.of("PA-003"),
                "客户希望规划长期养老现金流",
                List.of("pension", "risk"));

        assertThat(result.productItems()).hasSize(1);
        assertThat(result.productItems().getFirst().productCode()).isEqualTo("PA-003");
        assertThat(result.productItems().getFirst().highlights())
                .anyMatch(highlight -> highlight.contains("养老年金保险"));
        assertThat(result.complianceNotes())
                .anyMatch(note -> note.contains("MockProductAnalysisService"));
    }
}
