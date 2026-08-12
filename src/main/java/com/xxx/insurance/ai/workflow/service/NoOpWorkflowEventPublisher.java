package com.xxx.insurance.ai.workflow.service;

import com.xxx.insurance.ai.workflow.model.WorkflowSseEventType;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 非 local-db profile 下的空事件发布器，保持同步 Workflow 不依赖 SSE 基础设施。
 */
@Service
@Profile("!local-db")
public class NoOpWorkflowEventPublisher implements WorkflowEventPublisher {

    /** 默认 profile 不持久化或广播工作流事件。 */
    @Override
    public void publish(String workflowInstanceId,
                        String conversationId,
                        long executionFenceToken,
                        WorkflowSseEventType type,
                        String node,
                        Map<String, Object> data) {
        // Intentionally empty: SSE is available only with the local-db profile.
    }

    /** 默认 profile 没有持久化事件需要投递。 */
    @Override
    public void flushPersistedEvents(String workflowInstanceId) {
        // Intentionally empty: SSE is available only with the local-db profile.
    }

    /** 默认 profile 没有实时订阅连接需要关闭。 */
    @Override
    public void completeSubscribers(String workflowInstanceId) {
        // Intentionally empty: SSE is available only with the local-db profile.
    }
}
