package com.xxx.insurance.ai.workflow.sse.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.workflow.sse.config.WorkflowSseProperties;
import com.xxx.insurance.ai.workflow.config.WorkflowLifecycleProperties;
import com.xxx.insurance.ai.workflow.mapper.WorkflowExecutionMapper;
import com.xxx.insurance.ai.workflow.sse.mapper.WorkflowSseEventMapper;
import com.xxx.insurance.ai.workflow.model.WorkflowInstanceExecutionView;
import com.xxx.insurance.ai.workflow.sse.model.WorkflowSseEvent;
import com.xxx.insurance.ai.workflow.sse.model.WorkflowSseEventRecord;
import com.xxx.insurance.ai.workflow.sse.model.WorkflowSseEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
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
 * <p>OceanBase 事件表是唯一事实源，事件序号由实例行原子分配。本机写入后立即按游标读取
 * 以降低延迟，定时增量追踪负责补偿其他 JVM 发布的事件，因此不依赖负载均衡粘性会话。</p>
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

    private final WorkflowLifecycleProperties lifecycleProperties;

    private final TransactionTemplate transactionTemplate;

    private final Map<String, Object> workflowLocks = new ConcurrentHashMap<>();

    private final Map<String, CopyOnWriteArrayList<SseClient>> subscribers = new ConcurrentHashMap<>();

    /** 创建 SSE 事件服务并注入持久化、JSON 和连接策略依赖。 */
    public LocalDbWorkflowSseEventService(WorkflowSseEventMapper eventMapper,
                                          WorkflowExecutionMapper executionMapper,
                                          ObjectMapper objectMapper,
                                          WorkflowSseProperties properties,
                                          WorkflowLifecycleProperties lifecycleProperties,
                                          PlatformTransactionManager transactionManager) {
        this.eventMapper = eventMapper;
        this.executionMapper = executionMapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.lifecycleProperties = lifecycleProperties;
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
        return subscribeExistingRun(workflowInstanceId, lastEventId, false);
    }

    /**
     * 为已经原子抢占为 CONFIRMING 的实例重放遗漏事件并建立实时订阅。
     */
    public SseEmitter subscribeConfirmationResume(String workflowInstanceId, String lastEventId) {
        return subscribeExistingRun(workflowInstanceId, lastEventId, true);
    }

    /** 在同一实例锁内完成历史重放和实时订阅，避免重放与新事件之间出现窗口。 */
    private SseEmitter subscribeExistingRun(String workflowInstanceId,
                                            String lastEventId,
                                            boolean confirmationResume) {
        long afterSequence = parseLastSequence(workflowInstanceId, lastEventId);
        Object lock = workflowLocks.computeIfAbsent(workflowInstanceId, ignored -> new Object());
        synchronized (lock) {
            WorkflowInstanceExecutionView instance = executionMapper.findInstance(workflowInstanceId);
            if (instance == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow instance not found");
            }
            if (confirmationResume && !"CONFIRMING".equals(instance.status())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Workflow instance confirmation has not been claimed");
            }
            Long highWatermark = eventMapper.findHighWatermark(workflowInstanceId);
            List<WorkflowSseEventRecord> replayEvents = eventMapper.findReplayEvents(
                    workflowInstanceId, afterSequence, Instant.now());
            if (highWatermark != null && highWatermark > afterSequence
                    && (replayEvents.isEmpty() || replayEvents.getFirst().sequenceNo() > afterSequence + 1)) {
                throw new ResponseStatusException(HttpStatus.GONE, "Workflow SSE replay events have expired");
            }

            SseEmitter emitter = new SseEmitter(properties.connectionTimeout().toMillis());
            SseClient client = new SseClient(emitter, afterSequence);
            configureCallbacks(workflowInstanceId, client);
            replayEvents.forEach(record -> sendOrRemove(workflowInstanceId, client, toEvent(record)));
            if (confirmationResume || isOpenStatus(instance.status())) {
                subscribers.computeIfAbsent(workflowInstanceId, ignored -> new CopyOnWriteArrayList<>()).add(client);
            }
            else {
                emitter.complete();
            }
            return emitter;
        }
    }

    /** 仅在恢复订阅仍存在时发送兜底错误并关闭连接，避免重复系统级失败事件。 */
    public void failSubscribedRun(String workflowInstanceId,
                                  String conversationId,
                                  String message) {
        if (!subscribers.containsKey(workflowInstanceId)) {
            return;
        }
        WorkflowInstanceExecutionView instance = executionMapper.findInstance(workflowInstanceId);
        if (instance != null) {
            publish(workflowInstanceId, conversationId, instance.executionFenceToken(),
                    WorkflowSseEventType.ERROR, null,
                    Map.of("status", "FAILED", "message", message));
        }
        completeSubscribers(workflowInstanceId);
    }

    /** 在同一数据库事务中分配序号并写入事件，随后从事实表按序投递给本地订阅者。 */
    @Override
    public void publish(String workflowInstanceId,
                        String conversationId,
                        long executionFenceToken,
                        WorkflowSseEventType type,
                        String node,
                        Map<String, Object> data) {
        Object lock = workflowLocks.computeIfAbsent(workflowInstanceId, ignored -> new Object());
        synchronized (lock) {
            try {
                WorkflowSseEvent event = transactionTemplate.execute(status -> persistEvent(
                        workflowInstanceId, conversationId, executionFenceToken, type, node, data));
                if (event == null) {
                    throw new IllegalStateException("SSE event transaction returned no event");
                }
                deliverPersistedEvents(workflowInstanceId);
            }
            catch (Exception ex) {
                // SSE 属于观测与交付通道，失败不能回滚已成功的业务 Graph。
                log.error("[Workflow] action=sse-publish status=failed workflowInstanceId={} type={}",
                        workflowInstanceId, type.eventName(), ex);
            }
        }
    }

    /**
     * 从 OceanBase 事件事实表增量追踪所有本地活跃连接。
     *
     * <p>每个应用实例只维护自己持有的网络连接，但会读取其他实例写入的后续序号。
     * SseClient 在发送成功后原子推进游标，可抵御即时读取和定时轮询产生的重复投递。</p>
     */
    @Scheduled(fixedDelayString = "${insurance.ai.workflow.sse.database-poll-interval:500ms}")
    public void pollPersistedEvents() {
        subscribers.keySet().forEach(this::deliverPersistedEvents);
    }

    /** 从最慢客户端的游标开始读取事实表，并按 sequenceNo 顺序幂等投递。 */
    private void deliverPersistedEvents(String workflowInstanceId) {
        List<SseClient> clients = subscribers.get(workflowInstanceId);
        if (clients == null || clients.isEmpty()) {
            return;
        }
        long afterSequence = clients.stream()
                .mapToLong(SseClient::lastDeliveredSequence)
                .min()
                .orElse(0L);
        try {
            List<WorkflowSseEventRecord> records = eventMapper.findReplayEvents(
                    workflowInstanceId, afterSequence, Instant.now());
            records.stream()
                    .map(this::toEvent)
                    .forEach(event -> clients.forEach(client ->
                            sendOrRemove(workflowInstanceId, client, event)));
        }
        catch (Exception ex) {
            // 短暂查询失败不关闭连接，下一轮仍从客户端最后成功序号继续补偿。
            log.warn("[Workflow] action=sse-database-poll status=failed workflowInstanceId={}",
                    workflowInstanceId, ex);
        }
    }

    /** 在独立事务连接内原子分配序号并持久化事件。 */
    private WorkflowSseEvent persistEvent(String workflowInstanceId,
                                          String conversationId,
                                          long executionFenceToken,
                                          WorkflowSseEventType type,
                                          String node,
                                          Map<String, Object> data) {
        Instant occurredAt = Instant.now();
        if (eventMapper.allocateExecutionSequence(
                workflowInstanceId, lifecycleProperties.getInstanceId(),
                executionFenceToken, occurredAt) != 1) {
            throw new IllegalStateException("Workflow execution lease was lost before SSE event");
        }
        return persistAllocatedEvent(workflowInstanceId, conversationId, type, node, data, occurredAt);
    }

    /** 使用当前事务连接已经分配的 sequence 写入不可变事件事实。 */
    private WorkflowSseEvent persistAllocatedEvent(String workflowInstanceId,
                                                   String conversationId,
                                                   WorkflowSseEventType type,
                                                   String node,
                                                   Map<String, Object> data,
                                                   Instant occurredAt) {
        long sequence = eventMapper.lastAllocatedSequence();
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

    /**
     * 在调用方已有事务中持久化终态事件，不即时访问网络。事务提交后定时 Poller 会将该
     * Outbox 事实事件投递给任意 JVM 上的 SSE 连接。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void persistTransactionalEvent(String workflowInstanceId,
                                          String conversationId,
                                          long executionFenceToken,
                                          WorkflowSseEventType type,
                                          String node,
                                          Map<String, Object> data) {
        if (eventMapper.allocateTerminalSequence(workflowInstanceId, executionFenceToken) != 1) {
            throw new IllegalStateException("Workflow terminal fencing token was rejected for SSE event");
        }
        persistAllocatedEvent(workflowInstanceId, conversationId, type, node, data, Instant.now());
    }

    /** 在人工暂停事务内写入经过 WAITING_CONFIRM 状态和 fencing token 校验的事件。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void persistWaitingConfirmEvent(String workflowInstanceId,
                                           String conversationId,
                                           long executionFenceToken,
                                           String node,
                                           Map<String, Object> data) {
        if (eventMapper.allocateWaitingConfirmSequence(workflowInstanceId, executionFenceToken) != 1) {
            throw new IllegalStateException("Workflow waiting-confirm fencing token was rejected for SSE event");
        }
        persistAllocatedEvent(
                workflowInstanceId, conversationId, WorkflowSseEventType.HUMAN_CONFIRM,
                node, data, Instant.now());
    }

    /** 在事务提交后立即尝试投递事实表事件；失败时保留连接游标，交给下一轮数据库扫描补偿。 */
    @Override
    public void flushPersistedEvents(String workflowInstanceId) {
        try {
            deliverPersistedEvents(workflowInstanceId);
        }
        catch (Exception ex) {
            log.warn("[Workflow] action=sse-outbox-flush status=deferred workflowInstanceId={}",
                    workflowInstanceId, ex);
        }
    }

    /** 清理已超过 Last-Event-ID 重放保留期的 OceanBase SSE 事件。 */
    @Transactional(rollbackFor = Exception.class)
    public int purgeExpiredEvents(Instant now) {
        int deleted = eventMapper.deleteExpiredEvents(now);
        log.info("[Workflow] action=sse-event-purge status=success eventCount={}", deleted);
        return deleted;
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
        SseClient client = new SseClient(emitter, 0L);
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

    /** 串行发送单连接事件；发送失败即移除连接，后台工作流继续执行并持久化后续事件。 */
    private void sendOrRemove(String workflowInstanceId, SseClient client, WorkflowSseEvent event) {
        try {
            boolean sent = client.send(event);
            if (sent && isStreamTerminalEvent(event.type())) {
                client.emitter().complete();
                removeClient(workflowInstanceId, client);
            }
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
        return "RUNNING".equals(status) || "RESUMING".equals(status) || "CONFIRMING".equals(status);
    }

    /** 判断事件是否结束当前这一次 SSE 连接；人工确认后可通过独立恢复接口建立新连接。 */
    private boolean isStreamTerminalEvent(String eventType) {
        return WorkflowSseEventType.COMPLETE.eventName().equals(eventType)
                || WorkflowSseEventType.ERROR.eventName().equals(eventType)
                || WorkflowSseEventType.HUMAN_CONFIRM.eventName().equals(eventType);
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
    private static final class SseClient {

        private final SseEmitter emitter;

        private long lastDeliveredSequence;

        private SseClient(SseEmitter emitter, long lastDeliveredSequence) {
            this.emitter = emitter;
            this.lastDeliveredSequence = lastDeliveredSequence;
        }

        private SseEmitter emitter() {
            return emitter;
        }

        private synchronized long lastDeliveredSequence() {
            return lastDeliveredSequence;
        }

        /** 只发送游标之后的事件，并在网络写成功后推进游标。 */
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
