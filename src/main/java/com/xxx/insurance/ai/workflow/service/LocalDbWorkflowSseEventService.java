package com.xxx.insurance.ai.workflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.workflow.config.WorkflowSseProperties;
import com.xxx.insurance.ai.workflow.mapper.WorkflowExecutionMapper;
import com.xxx.insurance.ai.workflow.mapper.WorkflowSseEventMapper;
import com.xxx.insurance.ai.workflow.model.WorkflowInstanceExecutionView;
import com.xxx.insurance.ai.workflow.model.WorkflowSseEvent;
import com.xxx.insurance.ai.workflow.model.WorkflowSseEventRecord;
import com.xxx.insurance.ai.workflow.model.WorkflowSseEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * local-db profile 下的 SSE 事件存储、实时广播和断线重放服务。
 *
 * <p>同一 workflowInstanceId 的分配、写入和广播在同一 JVM 锁内保持顺序；事件序号由
 * OceanBase 实例行原子分配，因此多实例部署时持久化顺序仍不会冲突。</p>
 */
@Service
@Profile("local-db")
public class LocalDbWorkflowSseEventService implements WorkflowEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LocalDbWorkflowSseEventService.class);

    private static final TypeReference<Map<String, Object>> EVENT_DATA_TYPE = new TypeReference<>() {
    };

    private final WorkflowSseEventMapper eventMapper;

    private final WorkflowExecutionMapper executionMapper;

    private final ObjectMapper objectMapper;

    private final WorkflowSseProperties properties;

    private final TransactionTemplate transactionTemplate;

    private final Map<String, Object> workflowLocks = new ConcurrentHashMap<>();

    private final Map<String, CopyOnWriteArrayList<SseClient>> subscribers = new ConcurrentHashMap<>();

    /** 创建 SSE 事件服务并注入持久化、JSON 和连接策略依赖。 */
    public LocalDbWorkflowSseEventService(WorkflowSseEventMapper eventMapper,
                                          WorkflowExecutionMapper executionMapper,
                                          ObjectMapper objectMapper,
                                          WorkflowSseProperties properties,
                                          PlatformTransactionManager transactionManager) {
        this.eventMapper = eventMapper;
        this.executionMapper = executionMapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 为尚未创建数据库实例的新运行注册实时连接。
     *
     * <p>调用方必须随后使用同一个 workflowInstanceId 启动 MainWorkflowService。</p>
     */
    public SseEmitter subscribeNewRun(String workflowInstanceId) {
        SseEmitter emitter = new SseEmitter(properties.connectionTimeout().toMillis());
        register(workflowInstanceId, emitter);
        return emitter;
    }

    /**
     * 根据 Last-Event-ID 重放持久事件，并在工作流仍运行时衔接实时事件。
     */
    public SseEmitter reconnect(String workflowInstanceId, String lastEventId) {
        long afterSequence = parseLastSequence(workflowInstanceId, lastEventId);
        Object lock = workflowLocks.computeIfAbsent(workflowInstanceId, ignored -> new Object());
        synchronized (lock) {
            WorkflowInstanceExecutionView instance = executionMapper.findInstance(workflowInstanceId);
            if (instance == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow instance not found");
            }
            Long highWatermark = eventMapper.findHighWatermark(workflowInstanceId);
            List<WorkflowSseEventRecord> replayEvents = eventMapper.findReplayEvents(
                    workflowInstanceId, afterSequence, Instant.now());
            if (highWatermark != null && highWatermark > afterSequence
                    && (replayEvents.isEmpty() || replayEvents.getFirst().sequenceNo() > afterSequence + 1)) {
                throw new ResponseStatusException(HttpStatus.GONE, "Workflow SSE replay events have expired");
            }

            SseEmitter emitter = new SseEmitter(properties.connectionTimeout().toMillis());
            SseClient client = new SseClient(emitter);
            configureCallbacks(workflowInstanceId, client);
            replayEvents.forEach(record -> sendOrRemove(workflowInstanceId, client, toEvent(record)));
            if (isOpenStatus(instance.status())) {
                subscribers.computeIfAbsent(workflowInstanceId, ignored -> new CopyOnWriteArrayList<>()).add(client);
            }
            else {
                emitter.complete();
            }
            return emitter;
        }
    }

    /**
     * 在同一数据库事务中分配序号并写入事件，随后按序广播给当前订阅者。
     */
    @Override
    public void publish(String workflowInstanceId,
                        String conversationId,
                        WorkflowSseEventType type,
                        String node,
                        Map<String, Object> data) {
        Object lock = workflowLocks.computeIfAbsent(workflowInstanceId, ignored -> new Object());
        synchronized (lock) {
            try {
                WorkflowSseEvent event = transactionTemplate.execute(status -> persistEvent(
                        workflowInstanceId, conversationId, type, node, data));
                if (event == null) {
                    throw new IllegalStateException("SSE event transaction returned no event");
                }
                broadcast(workflowInstanceId, event);
            }
            catch (Exception ex) {
                // SSE 属于观测与交付通道，失败不能回滚已成功的业务 Graph。
                log.error("[Workflow] action=sse-publish status=failed workflowInstanceId={} type={}",
                        workflowInstanceId, type.eventName(), ex);
            }
        }
    }

    /** 在独立事务连接内原子分配序号并持久化事件。 */
    private WorkflowSseEvent persistEvent(String workflowInstanceId,
                                          String conversationId,
                                          WorkflowSseEventType type,
                                          String node,
                                          Map<String, Object> data) {
        if (eventMapper.allocateSequence(workflowInstanceId) != 1) {
            throw new IllegalStateException("Workflow instance not found for SSE event");
        }
        long sequence = eventMapper.lastAllocatedSequence();
        Instant occurredAt = Instant.now();
        WorkflowSseEvent event = new WorkflowSseEvent(
                workflowInstanceId + ":" + sequence,
                type.eventName(),
                workflowInstanceId,
                conversationId,
                node,
                sequence,
                occurredAt,
                data);
        eventMapper.insert(new WorkflowSseEventRecord(
                event.eventId(), workflowInstanceId, conversationId, sequence,
                event.type(), node, toJson(event.data()), occurredAt,
                occurredAt.plus(properties.eventRetention())));
        return event;
    }

    /** 完成并移除当前工作流的全部实时连接。 */
    @Override
    public void completeSubscribers(String workflowInstanceId) {
        List<SseClient> clients = subscribers.remove(workflowInstanceId);
        if (clients != null) {
            clients.forEach(client -> client.emitter().complete());
        }
        workflowLocks.remove(workflowInstanceId);
    }

    /** 新运行尚未落库即提交失败时，直接终止临时连接。 */
    public void failNewRun(String workflowInstanceId, Throwable error) {
        List<SseClient> clients = subscribers.remove(workflowInstanceId);
        if (clients != null) {
            clients.forEach(client -> client.emitter().completeWithError(error));
        }
        workflowLocks.remove(workflowInstanceId);
    }

    /** 注册客户端并绑定断开、超时和错误清理回调。 */
    private void register(String workflowInstanceId, SseEmitter emitter) {
        SseClient client = new SseClient(emitter);
        configureCallbacks(workflowInstanceId, client);
        subscribers.computeIfAbsent(workflowInstanceId, ignored -> new CopyOnWriteArrayList<>()).add(client);
    }

    /** 配置 SseEmitter 生命周期回调，确保断开后移除客户端引用。 */
    private void configureCallbacks(String workflowInstanceId, SseClient client) {
        Runnable remove = () -> removeClient(workflowInstanceId, client);
        client.emitter().onCompletion(remove);
        client.emitter().onTimeout(remove);
        client.emitter().onError(error -> remove.run());
    }

    /** 向当前连接广播同一条持久事件。 */
    private void broadcast(String workflowInstanceId, WorkflowSseEvent event) {
        List<SseClient> clients = subscribers.getOrDefault(workflowInstanceId, new CopyOnWriteArrayList<>());
        clients.forEach(client -> sendOrRemove(workflowInstanceId, client, event));
    }

    /** 串行发送单连接事件；发送失败即移除连接，后台工作流继续执行并持久化后续事件。 */
    private void sendOrRemove(String workflowInstanceId, SseClient client, WorkflowSseEvent event) {
        try {
            client.send(event);
        }
        catch (IOException | IllegalStateException ex) {
            removeClient(workflowInstanceId, client);
            log.debug("[Workflow] action=sse-send status=disconnected workflowInstanceId={} eventId={}",
                    workflowInstanceId, event.eventId());
        }
    }

    /** 从订阅集合移除一个已结束连接。 */
    private void removeClient(String workflowInstanceId, SseClient client) {
        List<SseClient> clients = subscribers.get(workflowInstanceId);
        if (clients != null) {
            clients.remove(client);
            if (clients.isEmpty()) {
                subscribers.remove(workflowInstanceId, clients);
            }
        }
    }

    /** 校验 Last-Event-ID 属于当前工作流并提取序号。 */
    private long parseLastSequence(String workflowInstanceId, String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0;
        }
        int separator = lastEventId.lastIndexOf(':');
        if (separator <= 0 || !workflowInstanceId.equals(lastEventId.substring(0, separator))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Last-Event-ID");
        }
        try {
            long sequence = Long.parseLong(lastEventId.substring(separator + 1));
            if (sequence < 0) {
                throw new NumberFormatException("negative sequence");
            }
            return sequence;
        }
        catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Last-Event-ID", ex);
        }
    }

    /** 判断实例是否仍可能产生实时事件。 */
    private boolean isOpenStatus(String status) {
        return "RUNNING".equals(status) || "RESUMING".equals(status);
    }

    /** 将数据库记录恢复为前端协议事件。 */
    private WorkflowSseEvent toEvent(WorkflowSseEventRecord record) {
        try {
            return new WorkflowSseEvent(
                    record.eventId(), record.eventType(), record.workflowInstanceId(), record.conversationId(),
                    record.nodeCode(), record.sequenceNo(), record.createdAt(),
                    objectMapper.readValue(record.payloadJson(), EVENT_DATA_TYPE));
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize workflow SSE event", ex);
        }
    }

    /** 序列化脱敏事件数据。 */
    private String toJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize workflow SSE event", ex);
        }
    }

    /** 对单个 SseEmitter 的 send 操作加锁，避免并行 DAG 节点交叉写响应。 */
    private record SseClient(SseEmitter emitter) {

        private synchronized void send(WorkflowSseEvent event) throws IOException {
            emitter.send(SseEmitter.event()
                    .id(event.eventId())
                    .name(event.type())
                    .data(event));
        }
    }
}
