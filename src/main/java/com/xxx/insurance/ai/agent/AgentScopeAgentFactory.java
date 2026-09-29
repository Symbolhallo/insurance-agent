package com.xxx.insurance.ai.agent;

import com.xxx.insurance.ai.config.AgentSafetyProperties;
import com.xxx.insurance.ai.agent.state.NamespacedAgentStateStore;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.stereotype.Component;

/**
 * 统一装配 AgentScope HarnessAgent，确保模型、ReAct 迭代上限、Tool 与隔离 Skill 仓库采用同一策略。
 *
 * <p>保留 Harness 默认的上下文溢出压缩和 Tool 结果淘汰；关闭文件系统、Shell、子 Agent、工作区、
 * Transcript 和 Harness Memory Hook，避免模型越过领域 Tool 白名单，并由项目既有 OceanBase 事务
 * 继续统一管理短期记忆、长期历史和调用审计。</p>
 */
@Component
public class AgentScopeAgentFactory {

    private final Model model;
    private final AgentSafetyProperties safetyProperties;

    private final AgentStateStore stateStore;

    public AgentScopeAgentFactory(Model model,
                                  AgentSafetyProperties safetyProperties,
                                  AgentStateStore stateStore) {
        this.model = model;
        this.safetyProperties = safetyProperties;
        this.stateStore = stateStore;
        this.safetyProperties.validate();
    }

    /** 创建领域 Agent；Toolkit 和 SkillRepository 均只包含当前业务域能力。 */
    public HarnessAgent create(String name,
                               String description,
                               String systemPrompt,
                               AgentSkillRepository skillRepository,
                               Object... tools) {
        Toolkit toolkit = new Toolkit();
        for (Object tool : tools) {
            toolkit.registerTool(tool);
        }
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(name)
                .description(description)
                .sysPrompt(systemPrompt)
                .model(model)
                .toolkit(toolkit)
                .stateStore(new NamespacedAgentStateStore(stateStore, name))
                .maxIters(safetyProperties.getMaxIterations())
                .disableFilesystemTools()
                .disableShellTool()
                .disableSubagents()
                .disableToolsConfig()
                .disableWorkspaceContext()
                .disableTranscript()
                .disableMemoryHooks();
        if (skillRepository != null) {
            builder.skillRepository(skillRepository);
        }
        return builder.build();
    }
}
