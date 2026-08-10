package com.xxx.insurance.ai.workflow.model;

import java.time.Instant;

/**
 * Workflow 步骤持久化记录。
 *
 * @param workflowStepId 工作流步骤编号
 * @param workflowInstanceId 所属工作流实例编号
 * @param stepCode 步骤定义编码
 * @param stepName 步骤显示名称
 * @param stepType 步骤类型，例如 SYSTEM、MODEL 或 AGENT
 * @param target 步骤实际调用的节点、模型或智能体标识
 * @param status 步骤执行状态
 * @param inputJson 步骤初始化时记录的输入 JSON
 * @param startedAt 步骤开始执行时间，初始化时可以为空
 * @param createdAt 步骤记录创建时间
 */
public record WorkflowStepRecord(
        String workflowStepId,
        String workflowInstanceId,
        String stepCode,
        String stepName,
        String stepType,
        String target,
        String status,
        String inputJson,
        Instant startedAt,
        Instant createdAt) {
}
