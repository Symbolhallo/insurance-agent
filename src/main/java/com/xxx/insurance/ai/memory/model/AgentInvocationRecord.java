package com.xxx.insurance.ai.memory.model;

import java.time.Instant;
import java.util.List;

/**
 * Agent 单次调用审计记录。
 *
 * <p>该对象不参与模型上下文拼接，只用于本地数据库审计、问题排查和后续指标看板。
 * 后续接入 Workflow 后，workflowInstanceId 与 workflowStepId 会把一次 Agent 调用挂接到
 * 工作流节点执行记录上。</p>
 *
 * @param invocationId Agent 单次调用编号
 * @param conversationId 关联的会话编号
 * @param agentName 被调用的智能体名称
 * @param traceId 链路追踪编号
 * @param workflowInstanceId 关联的工作流实例编号，独立调用时为空
 * @param workflowStepId 关联的工作流步骤编号，独立调用时为空
 * @param modelProvider 模型供应商或协议适配标识
 * @param modelName 模型名称
 * @param userId 用户编号
 * @param customerId 客户编号
 * @param operatorId 操作员编号
 * @param userMessage 用户原始输入
 * @param assistantAnswer 智能体最终回答
 * @param durationMs 调用耗时，单位毫秒
 * @param answerLength 回答字符长度
 * @param outputFormatValid 回答是否满足输出格式合同
 * @param missingSections 缺失的输出章节
 * @param status 调用状态
 * @param errorCode 错误码
 * @param errorMessage 错误信息
 * @param createdAt 调用记录创建时间
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
