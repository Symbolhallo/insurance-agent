package com.xxx.insurance.ai.memory.model;

import java.time.Instant;
import java.util.List;

/**
 * Agent 单次调用审计记录。
 *
 * <p>该对象不参与模型上下文拼接，只用于本地数据库审计、问题排查和后续指标看板。
 * 后续接入 Workflow 后，workflowInstanceId 与 workflowStepId 会把一次 Agent 调用挂接到
 * 工作流节点执行记录上。</p>
 */
public record AgentInvocationRecord(
        String invocationId,
        String conversationId,
        String agentName,
        String traceId,
        String workflowInstanceId,
        String workflowStepId,
        String modelProvider,
        String modelName,
        String userId,
        String customerId,
        String operatorId,
        String userMessage,
        String assistantAnswer,
        Long durationMs,
        Integer answerLength,
        Boolean outputFormatValid,
        List<String> missingSections,
        String status,
        String errorCode,
        String errorMessage,
        Instant createdAt) {
}
