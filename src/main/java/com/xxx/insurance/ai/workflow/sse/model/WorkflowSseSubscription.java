package com.xxx.insurance.ai.workflow.sse.model;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 新工作流的 SSE 订阅句柄。
 *
 * @param workflowInstanceId 在后台 Graph 提交前预分配的工作流实例编号，供 HTTP 响应头立即返回
 * @param emitter 已在事件交付服务中注册的当前 JVM SSE 连接
 */
public record WorkflowSseSubscription(
        String workflowInstanceId,
        SseEmitter emitter) {
}
