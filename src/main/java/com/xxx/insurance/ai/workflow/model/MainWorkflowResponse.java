package com.xxx.insurance.ai.workflow.model;

import com.xxx.insurance.product.model.ConfirmedProduct;
import com.xxx.insurance.product.model.ProductRecallResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 主工作流响应。
 */
@Schema(description = "主工作流响应")
public record MainWorkflowResponse(
        @Schema(description = "当前是否启用本地数据库 Workflow")
        boolean workflowEnabled,

        @Schema(description = "工作流编码")
        String workflowCode,

        @Schema(description = "工作流实例编号")
        String workflowInstanceId,

        @Schema(description = "当前 Checkpoint 编号，等待人工确认时返回")
        String checkpointId,

        @Schema(description = "工作流节点步骤编号")
        Map<String, String> workflowStepIds,

        @Schema(description = "会话编号")
        String conversationId,

        @Schema(description = "用户原始问题")
        String originalQuestion,

        @Schema(description = "当前问题与上一轮话题的关系")
        ConversationTopicRelation topicRelation,

        @Schema(description = "结合历史上下文改写后的标准问题")
        String alignedQuestion,

        @Schema(description = "上下文对齐阶段识别出的历史已确认信息")
        Map<String, List<String>> confirmedInformation,

        @Schema(description = "识别出的意图")
        String intent,

        @Schema(description = "产品召回判断结果")
        ProductRecallDecision productRecallDecision,

        @Schema(description = "产品候选召回结果；召回判断为 false 时为空")
        ProductRecallResult productRecallResult,

        @Schema(description = "是否等待产品人工确认")
        boolean humanConfirmRequired,

        @Schema(description = "流入上下文对齐节点的标准产品")
        List<ConfirmedProduct> resolvedProducts,

        @Schema(description = "Planner Agent 生成的结构化执行计划")
        WorkflowPlan plan,

        @Schema(description = "工作流状态")
        String status,

        @Schema(description = "最终输出")
        String finalAnswer,

        @Schema(description = "DAG 全部任务的执行结果")
        DagExecutionResult dagExecutionResult,

        @Schema(description = "实际执行的子智能体统一响应")
        SubAgentExecutionResult agentResponse,

        @Schema(description = "工作流耗时，单位毫秒")
        long durationMs,

        @Schema(description = "开始时间")
        Instant startedAt,

        @Schema(description = "结束时间")
        Instant endedAt,

        @Schema(description = "错误信息")
        String errorMessage) {
}
