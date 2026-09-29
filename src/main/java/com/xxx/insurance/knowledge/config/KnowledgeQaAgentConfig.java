package com.xxx.insurance.knowledge.config;

import com.xxx.insurance.ai.agent.AgentScopeAgentFactory;
import com.xxx.insurance.ai.agent.ReactAgentStreamingExecutor;
import com.xxx.insurance.ai.config.AiModelProperties;
import com.xxx.insurance.ai.config.SkillConfig;
import com.xxx.insurance.ai.memory.service.AgentMemoryService;
import com.xxx.insurance.knowledge.agent.KnowledgeQaAgent;
import com.xxx.insurance.knowledge.tool.InsuranceKnowledgeTool;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 使用 AgentScope 装配保险知识问答 HarnessAgent。 */
@Configuration
public class KnowledgeQaAgentConfig {

    public static final String KNOWLEDGE_QA_REACT_AGENT = "knowledgeQaReactAgent";
    public static final String KNOWLEDGE_QA_AGENT = "knowledgeQaAgent";

    private static final String INSTRUCTION = """
            你是金融保险业务知识问答智能体，只回答保险合同、保险责任、保险主体和业务流程等通用知识。
            回答前必须优先调用 insurance_knowledge_search；未检索到依据时明确说明知识库未命中。
            不编造法律法规、监管文件、产品条款或来源，不查询客户隐私，不替代人工法务、合规或核保结论。
            """;

    /** 创建只装载知识 Skill 与知识检索 Tool 的 AgentScope HarnessAgent。 */
    @Bean(KNOWLEDGE_QA_REACT_AGENT)
    public HarnessAgent knowledgeQaReactAgent(
            AgentScopeAgentFactory factory,
            @Qualifier(SkillConfig.KNOWLEDGE_QA_SKILL_REPOSITORY) AgentSkillRepository skills,
            InsuranceKnowledgeTool tool) {
        return factory.create(KnowledgeQaAgent.AGENT_NAME, KnowledgeQaAgent.AGENT_DESCRIPTION,
                INSTRUCTION, skills, tool);
    }

    @Bean(KNOWLEDGE_QA_AGENT)
    public KnowledgeQaAgent knowledgeQaAgent(
            @Qualifier(KNOWLEDGE_QA_REACT_AGENT) HarnessAgent reactAgent,
            AgentMemoryService agentMemoryService,
            AiModelProperties aiModelProperties,
            ReactAgentStreamingExecutor streamingExecutor) {
        return new KnowledgeQaAgent(reactAgent, agentMemoryService, aiModelProperties, streamingExecutor);
    }
}
