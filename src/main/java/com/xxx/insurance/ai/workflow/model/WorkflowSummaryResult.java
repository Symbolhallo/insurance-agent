package com.xxx.insurance.ai.workflow.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 主工作流汇总节点的执行结果。
 *
 * @param summaryId 汇总调用编号
 * @param modelInvoked 是否为多任务结果调用了模型
 * @param sourceTaskCount DAG 产生的任务结果总数
 * @param successfulTaskCount 成功参与回答的任务数
 * @param answer 汇总后的待审核答案
 * @param durationMs 汇总耗时，单位毫秒
 * @param summarizedAt 汇总完成时间
 */
@Schema(description = "主工作流汇总结果")
public record WorkflowSummaryResult(
        @Schema(description = "汇总调用编号", example = "wfs-001")
        String summaryId,

        @Schema(description = "是否为多任务结果调用了总结模型")
        boolean modelInvoked,

        @Schema(description = "DAG 产生的任务结果总数")
        int sourceTaskCount,

        @Schema(description = "成功参与回答的任务数")
        int successfulTaskCount,

        @Schema(description = "汇总后的待审核答案")
        String answer,

        @Schema(description = "汇总耗时，单位毫秒")
        long durationMs,

        @Schema(description = "汇总完成时间")
        Instant summarizedAt) {
}
