package com.xxx.insurance.ai.agent;

import java.time.Instant;
import java.util.List;

/**
 * 带会话记忆 Agent 的统一执行结果。
 *
 * @param invocationId 调用编号
 * @param answer 最终回答
 * @param durationMs 调用耗时
 * @param answeredAt 回答完成时间
 * @param answerLength 回答字符数
 * @param memoryEnabled 本次调用是否携带并写入会话记忆
 * @param historyMessageCount 携带的历史消息数
 * @param outputFormatValid 可选的领域输出合同检查结果
 * @param missingSections 输出合同缺失部分
 */
public record ConversationAgentExecutionResult(
        String invocationId,
        String answer,
        long durationMs,
        Instant answeredAt,
        int answerLength,
        boolean memoryEnabled,
        int historyMessageCount,
        Boolean outputFormatValid,
        List<String> missingSections) {
}
