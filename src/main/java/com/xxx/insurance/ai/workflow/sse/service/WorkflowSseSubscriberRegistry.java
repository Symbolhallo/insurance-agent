package com.xxx.insurance.ai.workflow.sse.service;

import com.xxx.insurance.ai.workflow.sse.model.WorkflowSseEvent;
import com.xxx.insurance.ai.workflow.sse.model.WorkflowSseEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 管理当前 JVM 的 SSE 连接、连接游标和 workflow 级串行锁。
 *
 * <p>数据库事件仍是事实源；本类只拥有易失的网络连接状态。发送成功后才推进连接游标，终态事件
 * 自动结束当前连接，断开回调负责清理引用。</p>
 */
@Component
public class WorkflowSseSubscriberRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkflowSseSubscriberRegistry.class);

    private final Map<String, Object> workflowLocks = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<SseClient>> subscribers = new ConcurrentHashMap<>();

    /** 返回 workflow 级串行锁，供发布、轮询和重连协调游标。 */
    public Object workflowLock(String workflowInstanceId) {
        return workflowLocks.computeIfAbsent(workflowInstanceId, ignored -> new Object());
    }

    /** 创建、注册并返回一个从序号0开始的新工作流连接。 */
    public SseEmitter registerNew(String workflowInstanceId, Duration timeout) {
        SseClient client = createClient(workflowInstanceId, 0L, timeout);
        add(workflowInstanceId, client);
        return client.emitter();
    }

    /** 创建已配置清理回调、但尚未加入实时订阅集合的重放连接。 */
    public SseClient createClient(String workflowInstanceId, long lastSequence, Duration timeout) {
        SseClient client = new SseClient(new SseEmitter(timeout.toMillis()), lastSequence);
        Runnable remove = () -> remove(workflowInstanceId, client);
        client.emitter().onCompletion(remove);
        client.emitter().onTimeout(remove);
        client.emitter().onError(error -> remove.run());
        return client;
    }

    /** 把完成历史重放的连接加入实时订阅集合。 */
    public void add(String workflowInstanceId, SseClient client) {
        subscribers.computeIfAbsent(workflowInstanceId, ignored -> new CopyOnWriteArrayList<>()).add(client);
    }

    public boolean contains(String workflowInstanceId) {
        return subscribers.containsKey(workflowInstanceId);
    }

    public Set<String> workflowIds() {
        return Set.copyOf(subscribers.keySet());
    }

    public List<SseClient> clients(String workflowInstanceId) {
        return subscribers.get(workflowInstanceId);
    }

    /** 投递一个事件；断开只移除当前连接，终态事件成功发送后结束当前 SSE 段。 */
    public void sendOrRemove(String workflowInstanceId, SseClient client, WorkflowSseEvent event) {
        try {
            boolean sent = client.send(event);
            if (sent && isStreamTerminalEvent(event.type())) {
                client.emitter().complete();
                remove(workflowInstanceId, client);
            }
        }
        catch (IOException | IllegalStateException ex) {
            remove(workflowInstanceId, client);
            log.debug("[Workflow] action=sse-send status=disconnected workflowInstanceId={} eventId={}",
                    workflowInstanceId, event.eventId());
        }
    }

    /** 正常完成并移除 workflow 的全部连接与串行锁。 */
    public void complete(String workflowInstanceId) {
        List<SseClient> clients = subscribers.remove(workflowInstanceId);
        if (clients != null) {
            clients.forEach(client -> client.emitter().complete());
        }
        workflowLocks.remove(workflowInstanceId);
    }

    /** 在工作流尚未落库时以原始异常结束临时连接。 */
    public void fail(String workflowInstanceId, Throwable error) {
        List<SseClient> clients = subscribers.remove(workflowInstanceId);
        if (clients != null) {
            clients.forEach(client -> client.emitter().completeWithError(error));
        }
        workflowLocks.remove(workflowInstanceId);
    }

    private void remove(String workflowInstanceId, SseClient client) {
        List<SseClient> clients = subscribers.get(workflowInstanceId);
        if (clients != null) {
            clients.remove(client);
            if (clients.isEmpty()) {
                subscribers.remove(workflowInstanceId, clients);
            }
        }
    }

    private boolean isStreamTerminalEvent(String eventType) {
        return WorkflowSseEventType.COMPLETE.eventName().equals(eventType)
                || WorkflowSseEventType.ERROR.eventName().equals(eventType)
                || WorkflowSseEventType.HUMAN_CONFIRM.eventName().equals(eventType);
    }

    /** 单个网络连接及其最后成功发送序号。 */
    public static final class SseClient {

        private final SseEmitter emitter;
        private long lastDeliveredSequence;

        private SseClient(SseEmitter emitter, long lastDeliveredSequence) {
            this.emitter = emitter;
            this.lastDeliveredSequence = lastDeliveredSequence;
        }

        public SseEmitter emitter() {
            return emitter;
        }

        public synchronized long lastDeliveredSequence() {
            return lastDeliveredSequence;
        }

        /** 按 sequence 去重，只有网络发送成功后才推进游标。 */
        private synchronized boolean send(WorkflowSseEvent event) throws IOException {
            if (event.sequence() <= lastDeliveredSequence) {
                return false;
            }
            emitter.send(SseEmitter.event()
                    .id(event.eventId())
                    .name(event.type())
                    .data(event));
            lastDeliveredSequence = event.sequence();
            return true;
        }
    }
}
