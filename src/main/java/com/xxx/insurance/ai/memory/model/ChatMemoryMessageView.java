package com.xxx.insurance.ai.memory.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 窗口记忆消息查询视图。
 */
@Schema(description = "窗口记忆消息查询视图")
public record ChatMemoryMessageView(
        @Schema(description = "消息编号")
        String messageId,

        @Schema(description = "会话编号")
        String conversationId,

        @Schema(description = "消息顺序")
        int messageOrder,

        @Schema(description = "消息类型")
        String messageType,

        @Schema(description = "消息文本")
        String textContent,

        @Schema(description = "消息元数据 JSON")
        String metadataJson,

        @Schema(description = "创建时间")
        Instant createdAt) {
}
