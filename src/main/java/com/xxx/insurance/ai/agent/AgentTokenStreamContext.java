package com.xxx.insurance.ai.agent;

/**
 * 一次工作流内模型 Token 流的最小发布上下文。
 *
 * @param workflowInstanceId 工作流实例编号
 * @param conversationId 会话编号
 * @param taskId DAG 任务编号；Summary 流为空
 * @param agentName 产生 Token 的 Agent 名称
 * @param phase 输出阶段，例如 SUB_AGENT 或 SUMMARY
 */
public record AgentTokenStreamContext(
        String workflowInstanceId,
        String conversationId,
        String taskId,
        String agentName,
        String phase) {
}
