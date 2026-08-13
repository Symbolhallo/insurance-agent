package com.xxx.insurance.ai.workflow.sse.service;

import com.xxx.insurance.ai.workflow.sse.model.WorkflowSseEventType;

import java.util.Map;

/**
 * Workflow 核心执行链向 SSE 基础设施发布脱敏生命周期事件的端口。
 */
public interface WorkflowEventPublisher {

    /**
     * 发布一个脱敏工作流事件。local-db 实现必须先校验当前 execution owner/fencing token/lease，
     * 在事务内分配工作流单调 sequence 并写入 OceanBase 事实表，再按 sequence 尝试投递本机连接；
     * 其他实例连接由数据库 Poller 获取。SSE 查询或发送失败不得回滚已经成功的业务 Graph。
     */
    void publish(String workflowInstanceId,
                 String conversationId,
                 long executionFenceToken,
                 WorkflowSseEventType type,
                 String node,
                 Map<String, Object> data);

    /**
     * 事务提交后立即从事实表读取各连接游标之后的事件并投递；HUMAN_CONFIRM、COMPLETE、ERROR 实际
     * emitter.send 成功后关闭本段连接，失败时保留游标并交给 Poller/Last-Event-ID 重放补偿。
     */
    void flushPersistedEvents(String workflowInstanceId);

    /** 主动完成并移除当前 JVM 上该工作流的全部订阅与实例锁；不会删除 OceanBase 事件事实。 */
    void completeSubscribers(String workflowInstanceId);
}
