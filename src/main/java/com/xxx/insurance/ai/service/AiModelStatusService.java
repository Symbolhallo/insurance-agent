package com.xxx.insurance.ai.service;

import io.agentscope.core.skill.repository.AgentSkillRepository;
import com.xxx.insurance.ai.config.AiModelProperties;
import com.xxx.insurance.ai.config.SkillConfig;
import com.xxx.insurance.ai.model.AiModelStatus;
import com.xxx.insurance.product.agent.ProductAnalysisAgent;
import com.xxx.insurance.product.tool.ProductAnalysisTool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * AI 模型联调状态服务。
 *
 * <p>该服务不调用模型，只读取应用当前装配状态。这样用户可以在 Swagger UI 中先确认
 * DeepSeek / DashScope 兼容模式的 baseUrl、model、API Key 配置状态，以及 ProductAnalysisAgent
 * 当前绑定的 Skill 和 Tool，再发起真实 Agent 调用。</p>
 */
@Service
public class AiModelStatusService {

    private static final String PROVIDER = "openai-compatible";

    private final AiModelProperties aiModelProperties;

    private final AgentSkillRepository productAnalysisSkillRepository;

    public AiModelStatusService(
            AiModelProperties aiModelProperties,
            @Qualifier(SkillConfig.PRODUCT_ANALYSIS_SKILL_REPOSITORY)
            AgentSkillRepository productAnalysisSkillRepository) {
        this.aiModelProperties = aiModelProperties;
        this.productAnalysisSkillRepository = productAnalysisSkillRepository;
    }

    public AiModelStatus currentStatus() {
        String apiKey = aiModelProperties.getApiKey();
        return new AiModelStatus(
                PROVIDER,
                aiModelProperties.getBaseUrl(),
                aiModelProperties.getModelName(),
                aiModelProperties.getTemperature(),
                StringUtils.hasText(apiKey),
                maskApiKey(apiKey),
                ProductAnalysisAgent.AGENT_NAME,
                productAnalysisSkillRepository.getAllSkillNames().size(),
                productAnalysisSkillRepository.getAllSkillNames(),
                List.of(ProductAnalysisTool.TOOL_NAME));
    }

    private String maskApiKey(String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            return "";
        }
        String trimmedApiKey = apiKey.trim();
        if (trimmedApiKey.length() <= 8) {
            return "****";
        }
        return trimmedApiKey.substring(0, 3)
                + "****"
                + trimmedApiKey.substring(trimmedApiKey.length() - 4);
    }
}
