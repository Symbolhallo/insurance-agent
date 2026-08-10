package com.xxx.insurance.ai.workflow.checkpoint;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.xxx.insurance.ai.workflow.checkpoint.config.GraphCheckpointProperties;
import com.xxx.insurance.ai.workflow.checkpoint.mapper.GraphCheckpointMapper;
import com.xxx.insurance.ai.workflow.checkpoint.model.GraphCheckpointRecord;
import com.xxx.insurance.ai.workflow.checkpoint.model.GraphCheckpointThreadRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于 OceanBase MySQL 模式的 Spring AI Alibaba Graph CheckpointSaver。
 *
 * <p>该实现直接遵循 {@link BaseCheckpointSaver} 合同，不继承 MemorySaver，避免生产环境
 * 将完整 State 常驻 JVM。每次 put 都持久化一个不可变 Checkpoint，并通过线程版本乐观锁
 * 原子推进 latestCheckpointId。携带 checkpointId 的 updateState 会生成带父节点的新分支，
 * 不覆盖历史记录。</p>
 */
public class OceanBaseCheckpointSaver implements BaseCheckpointSaver {

    public static final String METADATA_WORKFLOW_INSTANCE_ID = "workflowInstanceId";

    public static final String METADATA_CONVERSATION_ID = "conversationId";

    private static final Logger log = LoggerFactory.getLogger(OceanBaseCheckpointSaver.class);

    private static final String STATUS_ACTIVE = "ACTIVE";

    private static final String STATUS_COMPLETED = "COMPLETED";

    private static final String STATUS_FAILED = "FAILED";

    private final GraphCheckpointMapper mapper;

    private final GraphCheckpointStateCodec stateCodec;

    private final GraphCheckpointProperties properties;

    /** 创建 OceanBase Saver，并校验保留期、Schema 版本和写入重试配置。 */
    public OceanBaseCheckpointSaver(GraphCheckpointMapper mapper,
                                    GraphCheckpointStateCodec stateCodec,
                                    GraphCheckpointProperties properties) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.stateCodec = Objects.requireNonNull(stateCodec, "stateCodec must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.properties.validate();
    }

    /** 按 threadId 查询保留期内的全部 Checkpoint 历史。 */
    @Override
    @Transactional(readOnly = true)
    public Collection<Checkpoint> list(RunnableConfig config) {
        String threadId = requiredThreadId(config);
        return mapper.findCheckpoints(threadId, Instant.now()).stream()
                .map(this::toCheckpoint)
                .toList();
    }

    /** 按 checkpointId 查询指定快照；未指定时读取线程最新快照。 */
    @Override
    @Transactional(readOnly = true)
    public Optional<Checkpoint> get(RunnableConfig config) {
        String threadId = requiredThreadId(config);
        Instant now = Instant.now();
        if (config.checkPointId().isPresent()) {
            return Optional.ofNullable(mapper.findCheckpoint(threadId, config.checkPointId().get(), now))
                    .map(this::toCheckpoint);
        }
        GraphCheckpointThreadRecord thread = mapper.findReadableThread(threadId, now);
        if (thread == null || thread.latestCheckpointId() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.findCheckpoint(threadId, thread.latestCheckpointId(), now))
                .map(this::toCheckpoint);
    }

    /**
     * 将 Graph Runtime 生成的不可变 Checkpoint 写入 OceanBase，并原子推进线程最新版本。
     *
     * <p>RunnableConfig.threadId 对应 workflowInstanceId；乐观锁冲突时按配置重试。
     * 返回值携带新 checkpointId，供框架后续保存以及 updateState/withResume 恢复链路使用。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        String threadId = requiredThreadId(config);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getActiveRetention());
        mapper.insertThreadIfAbsent(
                threadId,
                metadata(config, METADATA_WORKFLOW_INSTANCE_ID),
                metadata(config, METADATA_CONVERSATION_ID),
                expiresAt,
                now);

        GraphCheckpointStateCodec.EncodedState encodedState = stateCodec.encode(checkpoint.getState());
        for (int attempt = 1; attempt <= properties.getMaxWriteRetries(); attempt++) {
            GraphCheckpointThreadRecord thread = mapper.findReadableThread(threadId, now);
            if (thread == null) {
                throw new IllegalStateException("Graph checkpoint thread is unavailable: " + threadId);
            }
            if (!STATUS_ACTIVE.equals(thread.status())) {
                throw new IllegalStateException("Graph checkpoint thread is not active: " + threadId
                        + ", status=" + thread.status());
            }

            long checkpointVersion = thread.version() + 1;
            int updated = mapper.advanceThreadVersion(
                    threadId,
                    thread.version(),
                    checkpoint.getId(),
                    expiresAt,
                    now);
            if (updated == 0) {
                log.debug("[Memory] type=checkpoint action=put status=retry threadId={} attempt={}",
                        threadId,
                        attempt);
                continue;
            }

            String parentCheckpointId = config.checkPointId().orElse(thread.latestCheckpointId());
            mapper.insertCheckpoint(new GraphCheckpointRecord(
                    checkpoint.getId(),
                    threadId,
                    parentCheckpointId,
                    checkpointVersion,
                    checkpoint.getNodeId(),
                    checkpoint.getNextNodeId(),
                    encodedState.payload(),
                    encodedState.contentType(),
                    properties.getStateSchemaVersion(),
                    now));
            log.debug("[Memory] type=checkpoint action=put status=success threadId={} checkpointId={} version={}",
                    threadId,
                    checkpoint.getId(),
                    checkpointVersion);
            return RunnableConfig.builder(config)
                    .checkPointId(checkpoint.getId())
                    .build();
        }
        throw new IllegalStateException("Graph checkpoint concurrent update exceeded retry limit: " + threadId);
    }

    /** 释放活动线程，并将 Checkpoint 保留期切换为完成态保留期。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Tag release(RunnableConfig config) {
        String threadId = requiredThreadId(config);
        List<Checkpoint> checkpoints = mapper.findCheckpoints(threadId, Instant.now()).stream()
                .map(this::toCheckpoint)
                .toList();
        Instant now = Instant.now();
        int updated = mapper.releaseThread(threadId, now.plus(properties.getCompletedRetention()), now);
        if (updated == 0) {
            throw new NoSuchElementException("Active Graph checkpoint thread not found: " + threadId);
        }
        log.info("[Memory] type=checkpoint action=release status=success threadId={} checkpointCount={}",
                threadId,
                checkpoints.size());
        return new Tag(threadId, checkpoints);
    }

    /** 将 Checkpoint 线程标记为已完成。 */
    @Transactional(rollbackFor = Exception.class)
    public void markCompleted(String threadId) {
        updateStatus(threadId, STATUS_COMPLETED, properties.getCompletedRetention());
    }

    /** 将 Checkpoint 线程标记为失败并保留现场供排查。 */
    @Transactional(rollbackFor = Exception.class)
    public void markFailed(String threadId) {
        updateStatus(threadId, STATUS_FAILED, properties.getActiveRetention());
    }

    /** 清理已超过保留期的 Checkpoint 和线程记录。 */
    @Transactional(rollbackFor = Exception.class)
    public PurgeResult purgeExpired(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        int checkpointCount = mapper.deleteExpiredCheckpoints(now);
        int threadCount = mapper.deleteExpiredThreads(now);
        log.info("[Memory] type=checkpoint action=purge status=success checkpointCount={} threadCount={}",
                checkpointCount,
                threadCount);
        return new PurgeResult(checkpointCount, threadCount);
    }

    /** 更新 Checkpoint 线程终态和新的过期时间。 */
    private void updateStatus(String threadId, String status, java.time.Duration retention) {
        Objects.requireNonNull(threadId, "threadId must not be null");
        Instant now = Instant.now();
        int updated = mapper.updateThreadStatus(threadId, status, now.plus(retention), now);
        if (updated == 0) {
            log.warn("[Memory] type=checkpoint action=status-update status=ignored threadId={} targetStatus={}",
                    threadId,
                    status);
        }
    }

    /** 将数据库记录的二进制状态恢复为框架 Checkpoint 对象。 */
    private Checkpoint toCheckpoint(GraphCheckpointRecord record) {
        if (record.stateSchemaVersion() > properties.getStateSchemaVersion()) {
            throw new IllegalStateException("Checkpoint state schema is newer than application: checkpoint="
                    + record.stateSchemaVersion() + ", application=" + properties.getStateSchemaVersion());
        }
        return Checkpoint.builder()
                .id(record.checkpointId())
                .nodeId(record.nodeId())
                .nextNodeId(record.nextNodeId())
                .state(stateCodec.decode(record.statePayload(), record.stateContentType()))
                .build();
    }

    /** 提取并校验 RunnableConfig 中必需的 threadId。 */
    private String requiredThreadId(RunnableConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        return config.threadId()
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("RunnableConfig.threadId is required"));
    }

    /** 从 RunnableConfig 元数据中读取工作流关联字段。 */
    private String metadata(RunnableConfig config, String key) {
        return config.metadata(key).map(String::valueOf).orElse(null);
    }

    /**
     * 过期 Checkpoint 清理结果。
     *
     * @param checkpointCount 删除的 Checkpoint 记录数量
     * @param threadCount 删除的 Graph 线程记录数量
     */
    public record PurgeResult(int checkpointCount, int threadCount) {
    }
}
