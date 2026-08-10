package com.xxx.insurance.ai.workflow.model;

import java.util.List;

/**
 * 发送给行内输出审核微应用的方法入参。
 *
 * @param reviewRequestId 单次审核请求编号，用于幂等和链路关联
 * @param workflowInstanceId 工作流实例编号
 * @param conversationId 会话编号
 * @param originalQuestion 用户原始问题
 * @param alignedQuestion 上下文对齐后的标准问题
 * @param candidateAnswer 待发布的候选答案
 * @param taskResults 生成候选答案的 DAG 任务结果
 */
public record OutputReviewRequest(
        String reviewRequestId,
        String workflowInstanceId,
        String conversationId,
        String originalQuestion,
        String alignedQuestion,
        String candidateAnswer,
        List<AgentTaskExecutionResult> taskResults) {

    /** 防御性复制任务结果，避免审核调用期间 Graph State 被外部修改。 */
    public OutputReviewRequest {
        taskResults = taskResults == null ? List.of() : List.copyOf(taskResults);
    }
}
