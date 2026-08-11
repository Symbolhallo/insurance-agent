package com.xxx.insurance.ai.workflow.service;

import com.xxx.insurance.ai.workflow.model.WorkflowSseEventType;

import java.util.Map;

/**
 * Workflow 核心执行链向 SSE 基础设施发布脱敏生命周期事件的端口。
 */
public interface WorkflowEventPublisher {

    /** 持久化并实时广播一个工作流事件；实现不得把发送失败传播到业务 Graph。 */
    void publish(String workflowInstanceId,
                 String conversationId,
                 long executionFenceToken,
                 WorkflowSseEventType type,
                 String node,
                 Map<String, Object> data);

    /** 关闭当前工作流全部实时订阅连接。 */
    void completeSubscribers(String workflowInstanceId);
}
