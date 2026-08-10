package com.xxx.insurance.ai.workflow.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * 上下文对齐模型的结构化输出合同。
 *
 * @param topicRelation 当前问题与上一轮话题的关系
 * @param rewrittenQuestion 结合当前问题和历史上下文改写后的精炼问题
 * @param confirmedInformation 从历史对话中识别出的已确认信息
 * @param entities 模型从当前问题和历史上下文中提取的业务实体
 */
@Schema(description = "上下文对齐模型的结构化输出")
public record ContextAlignmentModelOutput(
        @Schema(description = "当前问题与上一轮话题的关系")
        ConversationTopicRelation topicRelation,

        @Schema(description = "结合当前问题和历史上下文改写后的精炼问题")
        String rewrittenQuestion,

        @Schema(description = "从历史对话中识别出的已确认信息")
        Map<String, List<String>> confirmedInformation,

        @Schema(description = "模型从当前问题和历史上下文中提取的业务实体")
        List<WorkflowEntity> entities) {
}
