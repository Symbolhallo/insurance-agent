package com.xxx.insurance;

import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.xxx.insurance.ai.config.SkillConfig;
import com.xxx.insurance.product.agent.ProductAnalysisAgent;
import com.xxx.insurance.product.config.ProductAnalysisAgentConfig;
import com.xxx.insurance.product.formatter.ProductAnalysisAnswerInspector;
import com.xxx.insurance.product.model.ProductAnalysisAnswerInspection;
import com.xxx.insurance.product.model.ProductAnalysisChatRequest;
import com.xxx.insurance.product.model.ProductAnalysisRequest;
import com.xxx.insurance.product.model.ProductAnalysisResult;
import com.xxx.insurance.product.tool.ProductAnalysisTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.ai.openai.api-key=test-api-key")
@AutoConfigureMockMvc
@ActiveProfiles("test")
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
    private ProductAnalysisAnswerInspector productAnalysisAnswerInspector;

    @Autowired
    @Qualifier("productAnalysisToolCallbacks")
    private List<ToolCallback> productAnalysisToolCallbacks;

    @Autowired
    private MockMvc mockMvc;

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

    @Test
    void productAnalysisAgentRejectsBlankChatMessageWithoutCallingModel() {
        ProductAnalysisChatRequest request = new ProductAnalysisChatRequest(" ", "conversation-001");

        assertThatThrownBy(() -> productAnalysisAgent.chat(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message must not be blank");
    }

    @Test
    void productAnalysisChatApiRejectsBlankMessage() throws Exception {
        mockMvc.perform(post("/api/v1/product-analysis-agent/chat")
                        .header("X-Trace-Id", "test-trace-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": " ",
                                  "conversationId": "conversation-001"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Trace-Id", "test-trace-001"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON-400"))
                .andExpect(jsonPath("$.message").value("message: message must not be blank"))
                .andExpect(jsonPath("$.traceId").value("test-trace-001"));
    }

    @Test
    void aiModelStatusApiReturnsMaskedModelConfiguration() throws Exception {
        mockMvc.perform(get("/api/v1/ai/model/status")
                        .header("X-Trace-Id", "test-trace-002"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "test-trace-002"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.provider").value("openai-compatible"))
                .andExpect(jsonPath("$.data.apiKeyConfigured").value(true))
                .andExpect(jsonPath("$.data.apiKeyMasked").value("tes****-key"))
                .andExpect(jsonPath("$.data.activeAgent").value(ProductAnalysisAgent.AGENT_NAME))
                .andExpect(jsonPath("$.data.skillCount").value(2))
                .andExpect(jsonPath("$.data.skills[0]").value("batch-product-analysis"))
                .andExpect(jsonPath("$.data.skills[1]").value("limited-product-analysis"))
                .andExpect(jsonPath("$.data.tools[0]").value(ProductAnalysisTool.TOOL_NAME))
                .andExpect(jsonPath("$.traceId").value("test-trace-002"));
    }

    @Test
    void agentMemorySnapshotApiReturnsNoOpSnapshotByDefault() throws Exception {
        mockMvc.perform(get("/api/v1/ai/memory/conversations/local-test-001")
                        .header("X-Trace-Id", "test-trace-memory-001"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "test-trace-memory-001"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.memoryEnabled").value(false))
                .andExpect(jsonPath("$.data.conversationId").value("local-test-001"))
                .andExpect(jsonPath("$.data.conversation").doesNotExist())
                .andExpect(jsonPath("$.data.chatMessages").isEmpty())
                .andExpect(jsonPath("$.data.longTermMemories").isEmpty())
                .andExpect(jsonPath("$.data.summaries").isEmpty())
                .andExpect(jsonPath("$.data.invocations").isEmpty());
    }

    @Test
    void conversationSummaryApiReturnsNoOpResponseByDefault() throws Exception {
        mockMvc.perform(post("/api/v1/ai/memory/conversations/local-test-001/summaries")
                        .header("X-Trace-Id", "test-trace-summary-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "maxMemories": 20
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "test-trace-summary-001"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.memoryEnabled").value(false))
                .andExpect(jsonPath("$.data.modelInvoked").value(false))
                .andExpect(jsonPath("$.data.conversationId").value("local-test-001"))
                .andExpect(jsonPath("$.data.sourceMemoryCount").value(0));
    }

    @Test
    void conversationSummaryApiRejectsTooLargeMaxMemories() throws Exception {
        mockMvc.perform(post("/api/v1/ai/memory/conversations/local-test-001/summaries")
                        .header("X-Trace-Id", "test-trace-summary-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "maxMemories": 201
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON-400"))
                .andExpect(jsonPath("$.message").value("maxMemories: maxMemories must be less than or equal to 200"));
    }

    @Test
    void productAnalysisChatApiRejectsConversationIdLongerThanDatabaseColumn() throws Exception {
        mockMvc.perform(post("/api/v1/product-analysis-agent/chat")
                        .header("X-Trace-Id", "test-trace-memory-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "请分析 PA-001",
                                  "conversationId": "conversation-id-longer-than-sixty-four-characters-which-should-be-rejected"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON-400"))
                .andExpect(jsonPath("$.message").value("conversationId: conversationId length must be less than or equal to 64"));
    }

    @Test
    void productAnalysisSkillsDefineOutputContractAndToolRules() throws Exception {
        String limitedSkill = readClasspathResource("skills/product-analysis/limited-product-analysis/SKILL.md");
        String batchSkill = readClasspathResource("skills/product-analysis/batch-product-analysis/SKILL.md");

        assertThat(limitedSkill)
                .contains("allowed_tools:")
                .contains(ProductAnalysisTool.TOOL_NAME)
                .contains("## 分析结论")
                .contains("## 产品事实")
                .contains("## 适配分析")
                .contains("## 风险提示")
                .contains("## 后续建议")
                .contains("不得把产品分析工具返回之外的信息包装成产品事实");

        assertThat(batchSkill)
                .contains("allowed_tools:")
                .contains(ProductAnalysisTool.TOOL_NAME)
                .contains("## 对比结论")
                .contains("## 产品对比表")
                .contains("## 适配排序")
                .contains("## 关键风险")
                .contains("## 后续建议")
                .contains("不得把产品分析工具返回之外的信息包装成产品事实");
    }

    @Test
    void productAnalysisAnswerInspectorAcceptsLimitedAnalysisContract() {
        ProductAnalysisAnswerInspection inspection = productAnalysisAnswerInspector.inspect("""
                ## 分析结论
                适合进一步评估。
                ## 产品事实
                产品事实来自工具结果。
                ## 适配分析
                与客户需求部分匹配。
                ## 风险提示
                当前信息不足，需人工复核。
                ## 后续建议
                建议补充预算信息。
                """);

        assertThat(inspection.outputFormatValid()).isTrue();
        assertThat(inspection.missingSections()).isEmpty();
    }

    @Test
    void productAnalysisAnswerInspectorAcceptsBatchAnalysisContract() {
        ProductAnalysisAnswerInspection inspection = productAnalysisAnswerInspector.inspect("""
                ## 对比结论
                三款产品适配方向不同。
                ## 产品对比表
                | 产品 | 类型 | 核心保障/权益 | 适配点 | 主要限制 | 信息完整度 |
                | --- | --- | --- | --- | --- | --- |
                ## 适配排序
                当前依据有限。
                ## 关键风险
                当前信息不足，需人工复核。
                ## 后续建议
                建议人工复核。
                """);

        assertThat(inspection.outputFormatValid()).isTrue();
        assertThat(inspection.missingSections()).isEmpty();
    }

    @Test
    void productAnalysisAnswerInspectorReportsMissingSections() {
        ProductAnalysisAnswerInspection inspection = productAnalysisAnswerInspector.inspect("""
                ## 分析结论
                需要进一步评估。
                ## 产品事实
                产品事实来自工具结果。
                """);

        assertThat(inspection.outputFormatValid()).isFalse();
        assertThat(inspection.missingSections()).contains("## 适配分析", "## 风险提示", "## 后续建议");
    }

    @Test
    void openApiContainsProductAnalysisInvocationObservationFields() throws Exception {
        mockMvc.perform(get("/v3/api-docs")
                        .header("X-Trace-Id", "test-trace-003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.ProductAnalysisChatResponse.properties.invocationId").exists())
                .andExpect(jsonPath("$.components.schemas.ProductAnalysisChatResponse.properties.answeredAt").exists())
                .andExpect(jsonPath("$.components.schemas.ProductAnalysisChatResponse.properties.answerLength").exists())
                .andExpect(jsonPath("$.components.schemas.ProductAnalysisChatResponse.properties.durationMs").exists())
                .andExpect(jsonPath("$.components.schemas.ProductAnalysisChatResponse.properties.memoryEnabled").exists())
                .andExpect(jsonPath("$.components.schemas.ProductAnalysisChatResponse.properties.memoryMessageCount").exists())
                .andExpect(jsonPath("$.components.schemas.ProductAnalysisChatResponse.properties.outputFormatValid").exists())
                .andExpect(jsonPath("$.components.schemas.ProductAnalysisChatResponse.properties.missingSections").exists());
    }

    @Test
    void memoryWorkflowMigrationUsesSpringAiChatMemoryRepositoryShape() throws Exception {
        String migration = readClasspathResource("db/migration/V1__create_memory_workflow_tables.sql");
        String longTermMemoryMigration = readClasspathResource("db/migration/V2__create_long_term_memory_table.sql");

        assertThat(ChatMemory.CONVERSATION_ID).isEqualTo("chat_memory_conversation_id");
        assertThat(migration)
                .contains("create table if not exists ai_chat_memory")
                .contains("create table if not exists ai_conversation_summary")
                .contains("conversation_id")
                .contains("message_order")
                .contains("message_type")
                .contains("text_content")
                .contains("metadata_json")
                .doesNotContain("create table if not exists ai_message");
        assertThat(longTermMemoryMigration)
                .contains("create table if not exists ai_long_term_memory")
                .contains("memory_type")
                .contains("importance_score")
                .contains("archived")
                .contains("AI 长期记忆表");
    }

    private String readClasspathResource(String location) throws Exception {
        ClassPathResource resource = new ClassPathResource(location);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }
}
