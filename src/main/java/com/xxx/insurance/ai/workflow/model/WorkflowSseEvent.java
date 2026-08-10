package com.xxx.insurance.ai.workflow.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

/**
 * 可实时发送和持久化重放的工作流 SSE 事件。
 *
 * @param eventId 事件编号，格式为 workflowInstanceId:sequence
 * @param type 事件类型
 * @param workflowInstanceId 工作流实例编号
 * @param conversationId 会话编号
 * @param node 当前节点编码
 * @param sequence 工作流内单调递增序号
 * @param occurredAt 事件发生时间
 * @param data 已脱敏的事件数据
 */
@Schema(description = "工作流 SSE 事件")
public record WorkflowSseEvent(
        @Schema(description = "事件编号，格式为 workflowInstanceId:sequence")
        String eventId,

        @Schema(description = "事件类型")
        String type,

        @Schema(description = "工作流实例编号")
        String workflowInstanceId,

        @Schema(description = "会话编号")
        String conversationId,

        @Schema(description = "当前节点编码")
        String node,

        @Schema(description = "工作流内单调递增序号")
        long sequence,

        @Schema(description = "事件发生时间")
        Instant occurredAt,

        @Schema(description = "已脱敏的事件数据")
        Map<String, Object> data) {

    /** 防御性复制事件数据，避免持久化后被调用方修改。 */
    public WorkflowSseEvent {
        data = data == null ? Map.of() : Map.copyOf(data);
    }
}
