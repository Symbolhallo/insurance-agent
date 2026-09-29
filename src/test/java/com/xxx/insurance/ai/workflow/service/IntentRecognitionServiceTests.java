package com.xxx.insurance.ai.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.agent.AgentScopeModelExecutor;
import com.xxx.insurance.ai.agent.AgentScopeStructuredOutput;
import com.xxx.insurance.ai.workflow.model.AlignedWorkflowContext;
import com.xxx.insurance.ai.workflow.model.ConversationTopicRelation;
import com.xxx.insurance.ai.workflow.model.IntentRoutingResult;
import com.xxx.insurance.ai.workflow.model.ProductRecallDecision;
import com.xxx.insurance.ai.workflow.model.ProductRecallTrigger;
import com.xxx.insurance.ai.workflow.node.IntentRecognitionNode;
import com.xxx.insurance.knowledge.agent.KnowledgeQaAgent;
import com.xxx.insurance.product.agent.ProductAnalysisAgent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntentRecognitionServiceTests {

    @Test
    void routesGeneralInsuranceConceptToKnowledgeAgent() {
        IntentRoutingResult result = recognize("""
                {
                  "intentions": [{
                    "intent": "KNOWLEDGE_QA",
                    "intentionQuery": "保险合同的犹豫期是什么？",
                    "reason": "一般保险概念"
                  }],
                  "reason": "用户询问保险合同犹豫期的一般概念"
                }
                """, "保险合同的犹豫期是什么？");

        assertThat(result.intent()).isEqualTo(IntentRecognitionNode.KNOWLEDGE_QA_INTENT);
        assertThat(result.targetAgent()).isEqualTo(KnowledgeQaAgent.AGENT_NAME);
    }

    @Test
    void routesSpecificProductQuestionToProductAnalysisAgent() {
        IntentRoutingResult result = recognize("""
                {
                  "intentions": [{
                    "intent": "PRODUCT_ANALYSIS",
                    "intentionQuery": "分析 PA-001 安享一生终身寿险的退保风险",
                    "reason": "具体产品分析"
                  }],
                  "reason": "用户要求分析具体产品的退保风险"
                }
                """, "分析 PA-001 安享一生终身寿险的退保风险");

        assertThat(result.intent()).isEqualTo(IntentRecognitionNode.PRODUCT_ANALYSIS_INTENT);
        assertThat(result.targetAgent()).isEqualTo(ProductAnalysisAgent.AGENT_NAME);
    }

    @Test
    void splitsMixedQuestionIntoTwoControlledRoutes() {
        IntentRoutingResult result = recognize("""
                {
                  "intentions": [
                    {
                      "intent": "KNOWLEDGE_QA",
                      "intentionQuery": "解释保险合同犹豫期",
                      "reason": "一般保险概念"
                    },
                    {
                      "intent": "PRODUCT_ANALYSIS",
                      "intentionQuery": "分析 PA-001 的退保风险",
                      "reason": "具体产品分析"
                    }
                  ],
                  "reason": "问题同时包含知识问答和产品分析"
                }
                """, "解释犹豫期，并分析 PA-001 的退保风险");

        assertThat(result.intent()).isEqualTo(IntentRecognitionNode.MULTI_INTENT);
        assertThat(result.targetAgent()).isNull();
        assertThat(result.routes()).extracting(route -> route.targetAgent())
                .containsExactly(KnowledgeQaAgent.AGENT_NAME, ProductAnalysisAgent.AGENT_NAME);
    }

    @Test
    void mergesDuplicateIntentEntriesIntoOneControlledRoute() {
        IntentRoutingResult result = recognize("""
                {
                  "intentions": [
                    {
                      "intent": "KNOWLEDGE_QA",
                      "intentionQuery": "解释保险等待期的含义",
                      "reason": "一般保险概念"
                    },
                    {
                      "intent": "KNOWLEDGE_QA",
                      "intentionQuery": "说明等待期内出险如何处理",
                      "reason": "等待期业务规则"
                    }
                  ],
                  "reason": "用户询问等待期"
                }
                """, "解释保险等待期，并说明等待期内出险如何处理");

        assertThat(result.intent()).isEqualTo(IntentRecognitionNode.KNOWLEDGE_QA_INTENT);
        assertThat(result.routes()).hasSize(1);
        assertThat(result.routes().getFirst().intentionQuery())
                .isEqualTo("解释保险等待期的含义；说明等待期内出险如何处理");
        assertThat(result.routes().getFirst().reason())
                .isEqualTo("一般保险概念；等待期业务规则");
    }

    @Test
    void deduplicatesIdenticalIntentEntriesWithoutRepeatingText() {
        IntentRoutingResult result = recognize("""
                {
                  "intentions": [
                    {
                      "intent": "KNOWLEDGE_QA",
                      "intentionQuery": "保险等待期是什么意思？",
                      "reason": "一般保险概念"
                    },
                    {
                      "intent": "KNOWLEDGE_QA",
                      "intentionQuery": "保险等待期是什么意思？",
                      "reason": "一般保险概念"
                    }
                  ],
                  "reason": "用户询问保险等待期"
                }
                """, "保险等待期是什么意思？");

        assertThat(result.routes()).singleElement().satisfies(route -> {
            assertThat(route.intentionQuery()).isEqualTo("保险等待期是什么意思？");
            assertThat(route.reason()).isEqualTo("一般保险概念");
        });
    }

    @Test
    void stillRejectsUnknownIntentWhenAnotherEntryIsValid() {
        assertThatThrownBy(() -> recognize("""
                {
                  "intentions": [
                    {
                      "intent": "KNOWLEDGE_QA",
                      "intentionQuery": "解释保险等待期",
                      "reason": "一般保险概念"
                    },
                    {
                      "intent": "UNSUPPORTED",
                      "intentionQuery": "执行未知任务",
                      "reason": "未知分类"
                    }
                  ],
                  "reason": "混合输出"
                }
                """, "解释保险等待期"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Intent recognition model returned invalid intention");
    }

    private IntentRoutingResult recognize(String modelOutput, String rewrittenQuestion) {
        AgentScopeModelExecutor modelExecutor = mock(AgentScopeModelExecutor.class);
        when(modelExecutor.execute(any(), any())).thenReturn(modelOutput);
        return new IntentRecognitionService(
                modelExecutor, new AgentScopeStructuredOutput(new ObjectMapper()))
                .recognize(new AlignedWorkflowContext(
                "conversation-001",
                rewrittenQuestion,
                ConversationTopicRelation.NO_HISTORY,
                rewrittenQuestion,
                Map.of(),
                List.of(),
                new ProductRecallDecision(false, ProductRecallTrigger.NO_PRODUCT_MENTION, "无需召回"),
                List.of(),
                false,
                0,
                0,
                0,
                "trace-001",
                Instant.parse("2026-08-09T00:00:00Z")));
    }
}
