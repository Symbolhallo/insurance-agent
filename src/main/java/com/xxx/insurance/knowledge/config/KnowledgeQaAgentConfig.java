package com.xxx.insurance.knowledge.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.xxx.insurance.ai.config.AiModelProperties;
import com.xxx.insurance.ai.agent.ReactAgentStreamingExecutor;
import com.xxx.insurance.ai.config.SkillConfig;
import com.xxx.insurance.ai.memory.service.AgentMemoryService;
import com.xxx.insurance.knowledge.agent.KnowledgeQaAgent;
import com.xxx.insurance.knowledge.tool.InsuranceKnowledgeTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * 保险业务知识问答智能体装配配置。
 */
@Configuration
public class KnowledgeQaAgentConfig {

    public static final String KNOWLEDGE_QA_REACT_AGENT = "knowledgeQaReactAgent";

    public static final String KNOWLEDGE_QA_AGENT = "knowledgeQaAgent";

    public static final String KNOWLEDGE_QA_TOOL_CALLBACKS = "knowledgeQaToolCallbacks";

    private static final String INSTRUCTION = """
            你是金融保险业务知识问答智能体，只回答保险合同、保险责任、保险主体和业务流程等通用知识。

            Skill 的名称、描述、详细规则和输出合同由 Spring AI Alibaba SkillsAgentHook 渐进式注入。
            回答业务知识前必须优先使用可用的知识检索工具；未检索到依据时明确说明当前知识库未命中。

            合规要求：
            - 不编造法律法规、监管文件、产品条款和来源；
            - 不查询或推断客户保单、资产和身份信息；
            - 不替代人工客服、法务、合规、核保或理赔结论；
            - 具体规则以正式合同、现行监管要求和保险公司流程为准。
            """;

    /**
     * 将知识检索 Tool 对象转换为 Spring AI ToolCallback。
     *
     * @param knowledgeTool 保险知识检索工具
     * @return 仅供 KnowledgeQAAgent 使用的 ToolCallback 数组
     */
    @Bean(KNOWLEDGE_QA_TOOL_CALLBACKS)
    public ToolCallback[] knowledgeQaToolCallbacks(InsuranceKnowledgeTool knowledgeTool) {
        return ToolCallbacks.from(knowledgeTool);
    }

    /**
     * 创建知识问答 ReactAgent。
     *
     * <p>该 Agent 与产品分析 Agent 复用全局 ChatModel，但 Skill Hook 和 ToolCallback 完全隔离。
     * ReactAgent 负责 ReAct 循环：模型选择知识 Skill、调用 insurance_knowledge_search，框架将
     * Tool 结果回填模型后生成最终回答。</p>
     */
    @Bean(KNOWLEDGE_QA_REACT_AGENT)
    public ReactAgent knowledgeQaReactAgent(
            ChatModel chatModel,
            @Qualifier(SkillConfig.KNOWLEDGE_QA_SKILLS_AGENT_HOOK) SkillsAgentHook skillsAgentHook,
            @Qualifier(KNOWLEDGE_QA_TOOL_CALLBACKS) ToolCallback[] toolCallbacks) {
        return ReactAgent.builder()
                .name(KnowledgeQaAgent.AGENT_NAME)
                .description(KnowledgeQaAgent.AGENT_DESCRIPTION)
                .model(chatModel)
                .instruction(INSTRUCTION)
                .hooks(skillsAgentHook)
                .tools(Arrays.asList(toolCallbacks))
                .enableLogging(true)
                .build();
    }

    /**
     * 创建知识问答业务智能体门面。
     *
     * @param reactAgent 知识问答专属 ReactAgent
     * @param skillsAgentHook 知识问答专属 Skill Hook
     * @param agentMemoryService Agent 记忆与审计协调服务
     * @param aiModelProperties 当前模型配置，用于调用审计
     * @return 供 Controller 和 DAG Executor 调用的 KnowledgeQaAgent
     */
    @Bean(KNOWLEDGE_QA_AGENT)
    public KnowledgeQaAgent knowledgeQaAgent(
            @Qualifier(KNOWLEDGE_QA_REACT_AGENT) ReactAgent reactAgent,
            @Qualifier(SkillConfig.KNOWLEDGE_QA_SKILLS_AGENT_HOOK) SkillsAgentHook skillsAgentHook,
            AgentMemoryService agentMemoryService,
            AiModelProperties aiModelProperties,
            ReactAgentStreamingExecutor streamingExecutor) {
        return new KnowledgeQaAgent(
                reactAgent, skillsAgentHook, agentMemoryService, aiModelProperties, streamingExecutor);
    }
}
