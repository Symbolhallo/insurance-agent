package com.xxx.insurance.ai.workflow.sse.service;

import com.xxx.insurance.ai.agent.AgentTokenStreamContext;
import com.xxx.insurance.ai.agent.AgentTokenStreamSink;
import com.xxx.insurance.ai.workflow.config.WorkflowExecutionConfig;
import com.xxx.insurance.ai.workflow.sse.config.WorkflowSseProperties;
import com.xxx.insurance.ai.workflow.sse.model.WorkflowSseEventType;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 将模型增量内容合并为低延迟、可持久化和可重放的工作流 SSE 事件。
 *
 * <p>每个模型流的首块同步发布，确保前端尽快看到首字；后续小块按字符阈值或最大等待时间
 * 合并，流结束时强制刷新。这样不改变 OceanBase 事实源和 Last-Event-ID 语义，同时避免每个
 * 模型 Token 都开启一次数据库事务。</p>
 */
@Component
public class WorkflowAgentTokenStreamSink implements AgentTokenStreamSink {

    private static final Logger log = LoggerFactory.getLogger(WorkflowAgentTokenStreamSink.class);

    private final WorkflowEventPublisher workflowEventPublisher;

    private final WorkflowSseProperties properties;

    private final ScheduledExecutorService flushScheduler;

    private final Map<StreamKey, TokenBatch> batches = new ConcurrentHashMap<>();

    /** 创建 Token 合并器并注入事件发布端口、刷新策略和独立调度器。 */
    public WorkflowAgentTokenStreamSink(
            WorkflowEventPublisher workflowEventPublisher,
            WorkflowSseProperties properties,
            @Qualifier(WorkflowExecutionConfig.WORKFLOW_TOKEN_FLUSH_SCHEDULER)
            ScheduledExecutorService flushScheduler) {
        this.workflowEventPublisher = workflowEventPublisher;
        this.properties = properties;
        this.flushScheduler = flushScheduler;
    }

    /**
     * 首块立即发布；后续块按字符数或最大延迟合并。
     *
     * <p>{@code chunkIndex} 始终取当前合并批次最后一个原始块序号，现有前端去重逻辑无需修改。</p>
     */
    @Override
    public void publishToken(AgentTokenStreamContext context,
                             String streamId,
                             long chunkIndex,
                             String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        StreamKey key = new StreamKey(context.workflowInstanceId(), streamId);
        TokenBatch batch = batches.computeIfAbsent(key, ignored -> new TokenBatch(context, streamId));
        synchronized (batch) {
            if (batch.closed) {
                return;
            }
            if (!batch.firstChunkPublished) {
                batch.firstChunkPublished = true;
                publishBatch(batch.context, streamId, chunkIndex, chunkIndex, 1, content, false);
                return;
            }

            batch.append(chunkIndex, content);
            boolean maxDelayReached = System.nanoTime() - batch.pendingSinceNanos
                    >= properties.tokenBatchMaxDelay().toNanos();
            if (batch.pendingContent.length() >= properties.tokenBatchMaxCharacters() || maxDelayReached) {
                cancelScheduledFlush(batch);
                flushPending(batch);
            }
            else if (batch.scheduledFlush == null) {
                scheduleFlush(key, batch);
            }
        }
    }

    /** 刷新最后一个合并批次，再发布空正文结束标记。 */
    @Override
    public void complete(AgentTokenStreamContext context,
                         String streamId,
                         long chunkCount) {
        StreamKey key = new StreamKey(context.workflowInstanceId(), streamId);
        TokenBatch batch = batches.computeIfAbsent(key, ignored -> new TokenBatch(context, streamId));
        synchronized (batch) {
            if (batch.closed) {
                return;
            }
            batch.closed = true;
            cancelScheduledFlush(batch);
            flushPending(batch);
            batches.remove(key, batch);
            publishBatch(context, streamId, chunkCount, chunkCount, 0, "", true);
        }
    }

    /** 异常结束时保留已经生成的正文，但不伪造正常结束标记。 */
    @Override
    public void abort(AgentTokenStreamContext context, String streamId) {
        StreamKey key = new StreamKey(context.workflowInstanceId(), streamId);
        TokenBatch batch = batches.remove(key);
        if (batch == null) {
            return;
        }
        synchronized (batch) {
            batch.closed = true;
            cancelScheduledFlush(batch);
            try {
                flushPending(batch);
            }
            catch (RuntimeException ex) {
                log.warn("[Workflow] action=token-batch-abort-flush status=failed workflowInstanceId={} streamId={}",
                        context.workflowInstanceId(), streamId, ex);
            }
        }
    }

    /** 应用优雅关闭前尽力刷新不足一个批次的模型正文。 */
    @PreDestroy
    public void flushBeforeShutdown() {
        batches.forEach((key, batch) -> {
            synchronized (batch) {
                batch.closed = true;
                cancelScheduledFlush(batch);
                try {
                    flushPending(batch);
                }
                catch (RuntimeException ex) {
                    log.warn("[Workflow] action=token-batch-shutdown-flush status=failed workflowInstanceId={} streamId={}",
                            batch.context.workflowInstanceId(), batch.streamId, ex);
                }
                batches.remove(key, batch);
            }
        });
    }

    /** 为不足字符阈值的批次建立最大延迟刷新任务。 */
    private void scheduleFlush(StreamKey key, TokenBatch batch) {
        try {
            batch.scheduledFlush = flushScheduler.schedule(
                    () -> flushScheduled(key, batch),
                    properties.tokenBatchMaxDelay().toNanos(),
                    TimeUnit.NANOSECONDS);
        }
        catch (RejectedExecutionException ex) {
            // 关闭阶段调度器可能拒绝任务；同步刷新可确保已产生正文不丢失。
            flushPending(batch);
        }
    }

    /** 到达最大等待时间后刷新当前批次；后续模型块会建立新的定时任务。 */
    private void flushScheduled(StreamKey key, TokenBatch batch) {
        synchronized (batch) {
            batch.scheduledFlush = null;
            if (batch.closed || batches.get(key) != batch) {
                return;
            }
            try {
                flushPending(batch);
            }
            catch (RuntimeException ex) {
                log.warn("[Workflow] action=token-batch-flush status=failed workflowInstanceId={} streamId={}",
                        batch.context.workflowInstanceId(), batch.streamId, ex);
            }
        }
    }

    /** 按原始顺序发布当前待处理正文，并在发布成功后清空缓冲区。 */
    private void flushPending(TokenBatch batch) {
        if (batch.pendingChunkCount == 0) {
            return;
        }
        publishBatch(
                batch.context,
                batch.streamId,
                batch.pendingFirstChunkIndex,
                batch.pendingLastChunkIndex,
                batch.pendingChunkCount,
                batch.pendingContent.toString(),
                false);
        batch.clearPending();
    }

    /** 取消尚未执行的时间窗口刷新任务。 */
    private void cancelScheduledFlush(TokenBatch batch) {
        ScheduledFuture<?> scheduledFlush = batch.scheduledFlush;
        batch.scheduledFlush = null;
        if (scheduledFlush != null) {
            scheduledFlush.cancel(false);
        }
    }

    /** 发布一个首块、合并块或结束标记。 */
    private void publishBatch(AgentTokenStreamContext context,
                              String streamId,
                              long firstChunkIndex,
                              long lastChunkIndex,
                              int sourceChunkCount,
                              String content,
                              boolean last) {
        workflowEventPublisher.publish(
                context.workflowInstanceId(),
                context.conversationId(),
                context.executionFenceToken(),
                WorkflowSseEventType.AGENT_STREAM,
                node(context),
                eventData(context, streamId, firstChunkIndex, lastChunkIndex,
                        sourceChunkCount, content, last));
    }

    /** 构造支持并行 Agent 独立拼接和批次观测的稳定事件字段。 */
    private Map<String, Object> eventData(AgentTokenStreamContext context,
                                          String streamId,
                                          long firstChunkIndex,
                                          long lastChunkIndex,
                                          int sourceChunkCount,
                                          String content,
                                          boolean last) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("streamId", streamId);
        data.put("agentName", context.agentName());
        data.put("phase", context.phase());
        if (context.taskId() != null) {
            data.put("taskId", context.taskId());
        }
        data.put("content", content);
        data.put("firstChunkIndex", firstChunkIndex);
        data.put("chunkIndex", lastChunkIndex);
        data.put("sourceChunkCount", sourceChunkCount);
        data.put("last", last);
        data.put("deliveryMode", "LIVE_MODEL_STREAM");
        return Map.copyOf(data);
    }

    private String node(AgentTokenStreamContext context) {
        return switch (context.phase()) {
            case AgentTokenStreamContext.PHASE_PRODUCT_REFERENCE_RESOLUTION -> "resolve-product-reference";
            case AgentTokenStreamContext.PHASE_CONTEXT_ALIGNMENT -> "context-alignment";
            case AgentTokenStreamContext.PHASE_INTENT_RECOGNITION -> "intent-recognition";
            case AgentTokenStreamContext.PHASE_PLANNER -> "planner";
            case AgentTokenStreamContext.PHASE_SUMMARY -> "summary";
            default -> "agent-invoke";
        };
    }

    private record StreamKey(String workflowInstanceId, String streamId) {
    }

    /** 单个模型流在两个可见 SSE 批次之间的临时状态；所有字段都在实例锁内访问。 */
    private static final class TokenBatch {

        private final AgentTokenStreamContext context;

        private final String streamId;

        private final StringBuilder pendingContent = new StringBuilder();

        private boolean firstChunkPublished;

        private boolean closed;

        private long pendingFirstChunkIndex;

        private long pendingLastChunkIndex;

        private int pendingChunkCount;

        private long pendingSinceNanos;

        private ScheduledFuture<?> scheduledFlush;

        private TokenBatch(AgentTokenStreamContext context, String streamId) {
            this.context = context;
            this.streamId = streamId;
        }

        private void append(long chunkIndex, String content) {
            if (pendingChunkCount == 0) {
                pendingFirstChunkIndex = chunkIndex;
                pendingSinceNanos = System.nanoTime();
            }
            pendingLastChunkIndex = chunkIndex;
            pendingChunkCount++;
            pendingContent.append(content);
        }

        private void clearPending() {
            pendingContent.setLength(0);
            pendingFirstChunkIndex = 0L;
            pendingLastChunkIndex = 0L;
            pendingChunkCount = 0;
            pendingSinceNanos = 0L;
        }
    }
}
