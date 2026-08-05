package com.xxx.insurance.ai.memory.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 长期记忆查询视图。
 */
@Schema(description = "长期记忆查询视图")
public record LongTermMemoryView(
        @Schema(description = "长期记忆编号")
        String memoryId,

        @Schema(description = "会话编号")
        String conversationId,

        @Schema(description = "调用编号")
        String invocationId,

        @Schema(description = "智能体名称")
        String agentName,

        @Schema(description = "记忆类型")
        String memoryType,

        @Schema(description = "消息角色")
        String role,

        @Schema(description = "原文内容")
        String content,

        @Schema(description = "摘要")
        String summary,

        @Schema(description = "标签 JSON")
        String tagsJson,

        @Schema(description = "重要性评分")
        BigDecimal importanceScore,

        @Schema(description = "是否归档")
        boolean archived,

        @Schema(description = "扩展元数据 JSON")
        String metadataJson,

        @Schema(description = "业务事件发生时间")
        Instant occurredAt,

        @Schema(description = "创建时间")
        Instant createdAt) {
}
