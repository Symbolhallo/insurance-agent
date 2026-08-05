package com.xxx.insurance.ai.memory.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 会话摘要生成请求。
 *
 * @param maxMemories 本次最多读取的长期记忆条数
 */
@Schema(description = "会话摘要生成请求")
public record ConversationSummaryRequest(
        @Schema(description = "本次最多读取的长期记忆条数，最大 200", example = "100")
        @Min(value = 1, message = "maxMemories must be greater than or equal to 1")
        @Max(value = 200, message = "maxMemories must be less than or equal to 200")
        Integer maxMemories) {
}
