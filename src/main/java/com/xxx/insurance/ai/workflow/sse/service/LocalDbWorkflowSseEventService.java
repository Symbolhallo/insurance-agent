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
     * 为尚未创建数据库实例的新运行建立带配置超时的 SseEmitter，注册完成/超时/错误清理回调，并以
     * workflowInstanceId 保存本 JVM 订阅游标。调用方必须随后使用同一编号启动 MainWorkflowService，
     * 从而让 START 和首模型块可以在实例创建后立即通过事实表投递，避免先执行后订阅造成首事件丢失。
     */
    public SseEmitter subscribeNewRun(String workflowInstanceId) {
        SseEmitter emitter = new SseEmitter(properties.connectionTimeout().toMillis());
        register(workflowInstanceId, emitter);
        return emitter;
    }

    /**
     * 根据 Last-Event-ID 校验工作流归属并定位 sequence，在实例级锁内重放尚未过期的 OceanBase 事件；
     * 检测到保留期造成的序号缺口时返回 410。实例仍为 RUNNING/RESUMING/CONFIRMING 时，以最后成功发送
     * sequence 注册实时游标，保证历史重放与后续数据库轮询之间无窗口；终态实例重放完即结束连接。
     */
    public SseEmitter reconnect(String workflowInstanceId, String lastEventId) {
        return subscribeExistingRun(workflowInstanceId, lastEventId, false);
    }

    /**
     * 为已经由上层 CAS 抢占为 CONFIRMING 的实例建立恢复连接：先补发 Last-Event-ID 后的事实事件，再
     * 强制注册实时游标，即使恢复 Graph 尚未从 CONFIRMING 转回 RUNNING，也不会丢失后续模型和阶段事件。
     */
    public SseEmitter subscribeConfirmationResume(String workflowInstanceId, String lastEventId) {
        return subscribeExistingRun(workflowInstanceId, lastEventId, true);
    }

    /**
     * 完成重连核心链路：解析游标、校验实例/确认抢占状态、检测过期缺口、按序发送历史事件，并依据
     * 实例状态登记实时订阅或结束响应。全过程使用 workflowInstanceId 级锁与 SseClient 单连接锁，避免
     * 即时 publish、定时 Poller 和重连线程交叉推进同一游标。
     */
    private SseEmitter subscribeExistingRun(String workflowInstanceId,
                                            String lastEventId,
                                            boolean confirmationResume) {
        long afterSequence = parseLastSequence(workflowInstanceId, lastEventId);
        Object lock = workflowLocks.computeIfAbsent(workflowInstanceId, ignored -> new Object());
        synchronized (lock) {
            WorkflowInstanceExecutionView workflowInstance = requireSubscribableInstance(
                    workflowInstanceId, confirmationResume);
            Long highWatermark = eventMapper.findHighWatermark(workflowInstanceId);
            List<WorkflowSseEventRecord> replayEvents = eventMapper.findReplayEvents(
                    workflowInstanceId, afterSequence, Instant.now());
            rejectExpiredReplayGap(highWatermark, afterSequence, replayEvents);

            SseEmitter emitter = new SseEmitter(properties.connectionTimeout().toMillis());
            SseClient client = new SseClient(emitter, afterSequence);
            configureCallbacks(workflowInstanceId, client);
            sendReplayEvents(workflowInstanceId, client, replayEvents);
            if (confirmationResume || isOpenStatus(workflowInstance.status())) {
                subscribers.computeIfAbsent(workflowInstanceId, ignored -> new CopyOnWriteArrayList<>()).add(client);
            }
            else {
                emitter.complete();
            }
            return emitter;
        }
    }

    /** 读取工作流实例，并校验当前入口是否允许建立普通重连或确认恢复订阅。 */
    private WorkflowInstanceExecutionView requireSubscribableInstance(String workflowInstanceId,
                                                                      boolean confirmationResume) {
        WorkflowInstanceExecutionView workflowInstance = executionMapper.findInstance(workflowInstanceId);
        if (workflowInstance == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow instance not found");
        }
        if (confirmationResume && !"CONFIRMING".equals(workflowInstance.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Workflow instance confirmation has not been claimed");
        }
        return workflowInstance;
    }

    /** 当 Last-Event-ID 与当前水位之间的数据已超过保留期时拒绝不完整重放。 */
    private void rejectExpiredReplayGap(Long highWatermark,
                                        long afterSequence,
                                        List<WorkflowSseEventRecord> replayEvents) {
        if (highWatermark == null || highWatermark <= afterSequence) {
            return;
        }
        boolean firstExpectedEventAvailable = !replayEvents.isEmpty()
                && replayEvents.getFirst().sequenceNo() == afterSequence + 1;
        if (!firstExpectedEventAvailable) {
            throw new ResponseStatusException(HttpStatus.GONE, "Workflow SSE replay events have expired");
        }
    }

    /** 按数据库序号逐条投递历史事件，SseClient 负责连接内去重和游标推进。 */
    private void sendReplayEvents(String workflowInstanceId,
                                  SseClient client,
                                  List<WorkflowSseEventRecord> replayEvents) {
        for (WorkflowSseEventRecord replayEvent : replayEvents) {
            sendOrRemove(workflowInstanceId, client, toEvent(replayEvent));
        }
    }

    /**
     * 仅在本 JVM 仍持有恢复订阅时，用实例当前 fencing token 持久化一条脱敏 ERROR 事件并尝试投递，
     * 随后主动清理连接；若工作流事务层已写入终态错误或连接已结束，则不再制造重复系统级事件。
     */
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

    /**
     * 在 workflowInstanceId 级锁内发布执行期事件：事务中校验当前 owner、fencing token 和未过期 lease，
     * 原子递增实例 sequence 并写入带 expireAt 的 OceanBase 事实记录；提交后重新从事实表按序投递，而非
     * 直接发送内存对象。事件持久化、查询或网络发送异常仅记录日志，不反向回滚业务 Graph，后续 Poller
     * 或 Last-Event-ID 重连继续补偿已成功落库的事件。
     */
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

    /**
     * 以本 JVM 最慢连接的已发送游标为查询起点，按 sequenceNo 升序读取未过期事实，再让每个 SseClient
     * 自行跳过已发送事件。网络成功后才推进连接游标；查询/发送失败不关闭其他连接，下一轮扫描继续补偿。
     */
    private void deliverPersistedEvents(String workflowInstanceId) {
        List<SseClient> clients = subscribers.get(workflowInstanceId);
        if (clients == null || clients.isEmpty()) {
            return;
        }
        long earliestDeliveredSequence = clients.stream()
                .mapToLong(SseClient::lastDeliveredSequence)
                .min()
                .orElse(0L);
        try {
            List<WorkflowSseEventRecord> persistedEvents = eventMapper.findReplayEvents(
                    workflowInstanceId, earliestDeliveredSequence, Instant.now());
            for (WorkflowSseEventRecord persistedEvent : persistedEvents) {
                WorkflowSseEvent event = toEvent(persistedEvent);
                for (SseClient client : clients) {
                    sendOrRemove(workflowInstanceId, client, event);
                }
            }
        }
        catch (Exception ex) {
            // 短暂查询失败不关闭连接，下一轮仍从客户端最后成功序号继续补偿。
            log.warn("[Workflow] action=sse-database-poll status=failed workflowInstanceId={}",
                    workflowInstanceId, ex);
        }
    }

    /**
     * 在独立事务连接中通过实例行 CAS 校验执行权并分配 sequence，再写入不可变事件记录；CAS 失败表示
     * 当前 Graph 已失去 owner、fencing token 或 lease，事件不会以旧执行代次进入事实表。
     */
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

    /**
     * 读取当前事务刚分配的 sequence，生成稳定 eventId={workflowInstanceId}:{sequence}，序列化脱敏载荷，
     * 并按 eventRetention 计算 expireAt 后写入 OceanBase；返回值只用于确认持久化结果，不绕过事实表投递。
     */
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

    /**
     * 在 WorkflowPauseService 的现有事务中确认实例已进入 WAITING_CONFIRM 且 fencing token 仍匹配，
     * 分配 sequence 并写 HUMAN_CONFIRM Outbox；网络发送必须等外层事务提交后由 flush/Poller 完成。
     */
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

    /** 完成并移除当前 JVM 上全部 SseEmitter、订阅游标和实例锁；保留数据库事实供重连与审计。 */
    @Override
    public void completeSubscribers(String workflowInstanceId) {
        List<SseClient> clients = subscribers.remove(workflowInstanceId);
        if (clients != null) {
            clients.forEach(client -> client.emitter().complete());
        }
        workflowLocks.remove(workflowInstanceId);
    }

    /**
     * 处理“已订阅但工作流尚未成功落库/提交线程池”的失败窗口：以原始异常结束所有临时 emitter，
     * 移除订阅和实例锁。此时没有可靠事件事实可重放，因此不伪造数据库 ERROR 事件。
     */
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

    /**
     * 串行调用单连接 emitter.send，并仅在成功后推进游标；HUMAN_CONFIRM/COMPLETE/ERROR 发送成功后完成
     * 当前连接。I/O、超时或已关闭异常只移除该连接，后台 Graph 和 OceanBase 事件持久化继续运行。
     */
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
        if (status == null) {
            return false;
        }
        return switch (status) {
            case "RUNNING", "RESUMING", "CONFIRMING" -> true;
            default -> false;
        };
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

        /**
         * 在单连接锁内按 sequence 去重并组装标准 SSE id/name/data；只有 emitter.send 正常返回才推进
         * lastDeliveredSequence，保证即时 flush、定时 Poller 和重连并发时不会重复或越过失败事件。
         */
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
