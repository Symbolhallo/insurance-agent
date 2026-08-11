package com.xxx.insurance.ai.agent;

import org.springframework.util.StringUtils;

/**
 * 子智能体执行时由编排层传入的链路上下文。
 *
 * @param workflowInstanceId 所属工作流实例编号，独立调用时为空
 * @param workflowStepId 所属工作流步骤编号，独立调用时为空
 * @param executionFenceToken 当前工作流执行权代次，独立调用时为0
 * @param originalUserMessage 进入工作流时的原始用户问题，用于审计记录
 * @param conversationMemoryEnabled 是否允许本次子智能体调用直接读写会话记忆
 * @param taskId Planner 任务编号，独立调用时为空
 * @param tokenStreamingEnabled 是否使用 ReactAgent.stream 执行模型
 */
public record AgentExecutionContext(
        String workflowInstanceId,
        String workflowStepId,
        long executionFenceToken,
        String originalUserMessage,
        boolean conversationMemoryEnabled,
        String taskId,
        boolean tokenStreamingEnabled) {

    /**
     * 兼容单 Agent 调用的构造方式，默认允许该 Agent 直接读写会话记忆。
     */
    public AgentExecutionContext(String workflowInstanceId,
                                 String workflowStepId,
                                 String originalUserMessage) {
        this(workflowInstanceId, workflowStepId, originalUserMessage, true, null, false);
    }

    /** 创建 Workflow 子任务上下文，显式控制会话记忆和内部模型流式执行。 */
    public AgentExecutionContext(String workflowInstanceId,
                                 String workflowStepId,
                                 String originalUserMessage,
                                 boolean conversationMemoryEnabled,
                                 String taskId,
                                 boolean tokenStreamingEnabled) {
        this(workflowInstanceId, workflowStepId, 0L, originalUserMessage,
                conversationMemoryEnabled, taskId, tokenStreamingEnabled);
    }

    /**
     * 创建不属于 Workflow 的独立 Agent 调用上下文。
     */
    public static AgentExecutionContext standalone(String userMessage) {
        return new AgentExecutionContext(null, null, 0L, userMessage, true, null, false);
    }

    /**
     * 审计时优先保留进入工作流的用户原话，缺失时回退到 Agent 实际输入。
     */
    public String auditedUserMessage(String fallbackMessage) {
        return StringUtils.hasText(originalUserMessage) ? originalUserMessage : fallbackMessage;
    }
}
