package com.xxx.insurance.ai.workflow.model;

import io.swagger.v3.oas.annotations.media.Schema;
import com.xxx.insurance.product.model.ConfirmedProduct;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 当前请求与历史记忆完成对齐后的标准上下文。
 *
 * @param conversationId 会话编号
 * @param originalQuestion 用户本次提交的原始问题
 * @param topicRelation 当前问题与上一轮话题的关系
 * @param rewrittenQuestion 结合历史记忆改写后的精炼问题
 * @param confirmedInformation 从历史对话中识别出的已确认信息
 * @param entities 从当前问题和历史记忆中识别出的业务实体
 * @param productRecallDecision 产品候选召回判断
 * @param resolvedProducts 产品实体解析或人工确认后得到的标准产品
 * @param memoryEnabled 本次上下文对齐是否启用数据库记忆
 * @param chatMessageCount 本次读取的窗口记忆消息数量
 * @param longTermMemoryCount 本次读取的长期记忆数量
 * @param summaryCount 本次读取的会话摘要数量
 * @param traceId 当前请求链路追踪编号
 * @param receivedAt 请求进入上下文对齐阶段的时间
 */
@Schema(description = "工作流上下文对齐结果")
public record AlignedWorkflowContext(
        @Schema(description = "会话编号", example = "workflow-local-001")
        String conversationId,

        @Schema(description = "用户本次提交的原始问题", example = "它有哪些风险？")
        String originalQuestion,

        @Schema(description = "当前问题与上一轮话题的关系")
        ConversationTopicRelation topicRelation,

        @Schema(description = "结合历史记忆改写后的精炼问题", example = "PA-001 风险分析")
        String rewrittenQuestion,

        @Schema(description = "从历史对话中识别出的已确认信息")
        Map<String, List<String>> confirmedInformation,

        @Schema(description = "从当前问题和历史记忆中识别出的业务实体")
        List<WorkflowEntity> entities,

        @Schema(description = "产品候选召回判断")
        ProductRecallDecision productRecallDecision,

        @Schema(description = "产品实体解析或人工确认后得到的标准产品")
        List<ConfirmedProduct> resolvedProducts,

        @Schema(description = "本次上下文对齐是否启用数据库记忆", example = "true")
        boolean memoryEnabled,

        @Schema(description = "本次读取的窗口记忆消息数量", example = "2")
        int chatMessageCount,

        @Schema(description = "本次读取的长期记忆数量", example = "4")
        int longTermMemoryCount,

        @Schema(description = "本次读取的会话摘要数量", example = "1")
        int summaryCount,

        @Schema(description = "当前请求链路追踪编号")
        String traceId,

        @Schema(description = "请求进入上下文对齐阶段的时间")
        Instant receivedAt) {
}
