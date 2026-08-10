package com.xxx.insurance.ai.workflow.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * 行内输出审核微应用的统一返回结果。
 *
 * @param reviewRequestId 审核请求编号
 * @param decision 发布决策
 * @param publishableAnswer 唯一允许继续流向 Summary 的文本
 * @param reasons 审核原因或命中的规则说明
 * @param mockData 当前是否为本地 Mock 返回
 * @param durationMs 审核调用耗时，单位毫秒
 * @param reviewedAt 审核完成时间
 */
@Schema(description = "输出审核结果")
public record OutputReviewResult(
        @Schema(description = "审核请求编号")
        String reviewRequestId,

        @Schema(description = "发布决策")
        OutputReviewDecision decision,

        @Schema(description = "审核后唯一允许发布的文本")
        String publishableAnswer,

        @Schema(description = "审核原因或命中的规则说明")
        List<String> reasons,

        @Schema(description = "是否为本地 Mock 返回")
        boolean mockData,

        @Schema(description = "审核调用耗时，单位毫秒")
        long durationMs,

        @Schema(description = "审核完成时间")
        Instant reviewedAt) {

    /** 防御性复制审核原因列表，保证写入 Graph State 后保持不可变。 */
    public OutputReviewResult {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
