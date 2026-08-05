package com.xxx.insurance.ai.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * AI 模型联调状态。
 *
 * @param provider 当前模型协议或供应商适配方向
 * @param baseUrl 模型服务地址
 * @param model 模型名称
 * @param temperature 采样温度
 * @param apiKeyConfigured API Key 是否已配置
 * @param apiKeyMasked API Key 脱敏展示
 * @param activeAgent 当前 Phase1 活跃智能体
 * @param skillCount 当前智能体加载的 Skill 数量
 * @param skills 当前智能体 Skill 名称
 * @param tools 当前智能体 Tool 名称
 */
@Schema(description = "AI 模型联调状态")
public record AiModelStatus(
        @Schema(description = "当前模型协议或供应商适配方向", example = "openai-compatible")
        String provider,

        @Schema(description = "模型服务地址", example = "https://api.deepseek.com")
        String baseUrl,

        @Schema(description = "模型名称", example = "deepseek-chat")
        String model,

        @Schema(description = "采样温度", example = "0.2")
        Double temperature,

        @Schema(description = "API Key 是否已配置", example = "true")
        boolean apiKeyConfigured,

        @Schema(description = "API Key 脱敏展示", example = "sk-****abcd")
        String apiKeyMasked,

        @Schema(description = "当前 Phase1 活跃智能体", example = "product-analysis-agent")
        String activeAgent,

        @Schema(description = "当前智能体加载的 Skill 数量", example = "2")
        int skillCount,

        @Schema(description = "当前智能体 Skill 名称")
        List<String> skills,

        @Schema(description = "当前智能体 Tool 名称")
        List<String> tools) {
}
