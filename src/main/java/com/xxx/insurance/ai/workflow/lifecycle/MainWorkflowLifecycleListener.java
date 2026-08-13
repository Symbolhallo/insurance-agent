package com.xxx.insurance.ai.workflow.lifecycle;

import com.alibaba.cloud.ai.graph.GraphLifecycleListener;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.xxx.insurance.ai.workflow.mapper.WorkflowExecutionMapper;
import com.xxx.insurance.ai.workflow.model.AlignedWorkflowContext;
import com.xxx.insurance.ai.workflow.model.MainWorkflowRequest;
import com.xxx.insurance.ai.workflow.model.MainWorkflowStateKeys;
import com.xxx.insurance.ai.workflow.model.WorkflowNodeDefinition;
import com.xxx.insurance.ai.workflow.sse.model.WorkflowSseEventType;
import com.xxx.insurance.ai.workflow.sse.service.WorkflowEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Main Graph 通用生命周期观测器。
 *
 * <p>基于 Spring AI Alibaba 1.1.2.0 GraphLifecycleListener 记录 Graph/Node 日志、耗时和
 * Stage SSE。框架会隔离 Listener 异常，所以这里只做非关键观测；Lease/Fence 与步骤状态
 * CAS 仍由 WorkflowNodeExecutionGuard 在节点执行链内强制实施。</p>
 */
@Component
@Profile("local-db")
public class MainWorkflowLifecycleListener implements GraphLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(MainWorkflowLifecycleListener.class);

    private final WorkflowExecutionMapper workflowExecutionMapper;

    private final WorkflowEventPublisher workflowEventPublisher;

    private final ConcurrentMap<String, Long> nodeStartedNanos = new ConcurrentHashMap<>();

    /**
     * 创建 Main Graph 生命周期观测器：Mapper 仅在 State 尚未携带 conversationId 时回查实例，
     * Publisher 负责把脱敏后的节点事件持久化到 OceanBase 并衔接本机即时投递/多实例轮询。
     */
    public MainWorkflowLifecycleListener(WorkflowExecutionMapper workflowExecutionMapper,
                                         WorkflowEventPublisher workflowEventPublisher) {
        this.workflowExecutionMapper = workflowExecutionMapper;
        this.workflowEventPublisher = workflowEventPublisher;
    }

    /** 记录 Graph 整体启动日志；执行权校验和实例状态变更不在 Listener 中完成。 */
    @Override
    public void onStart(String nodeId, Map<String, Object> state, RunnableConfig config) {
        log.info("[Workflow] action=graph-start status=running workflowInstanceId={}", workflowInstanceId(state));
    }

    /**
     * 在受支持节点执行前记录单次执行的单调时钟起点，发布 RUNNING 阶段事件并记录链路日志。
     * 事件只包含节点名和状态，不会把完整 Graph State 或客户数据发送到前端。
     */
    @Override
    public void before(String nodeId, Map<String, Object> state, RunnableConfig config, Long curTime) {
        nodeDefinition(nodeId).ifPresent(definition -> {
            nodeStartedNanos.put(executionKey(state, nodeId), System.nanoTime());
            publishStage(definition, state, "RUNNING", null);
            log.info("[Workflow] action=node-start node={} workflowInstanceId={}",
                    nodeId, workflowInstanceId(state));
        });
    }

    /**
     * 节点成功后回收计时状态、计算耗时、发布 SUCCESS 阶段事件并记录完成日志；
     * 步骤表 SUCCESS CAS 和 Lease/Fence 校验仍由 WorkflowNodeExecutionGuard 完成。
     */
    @Override
    public void after(String nodeId, Map<String, Object> state, RunnableConfig config, Long curTime) {
        nodeDefinition(nodeId).ifPresent(definition -> {
            long elapsedMillis = elapsedMillis(state, nodeId);
            publishStage(definition, state, "SUCCESS", null);
            log.info("[Workflow] action=node-complete status=success node={} workflowInstanceId={} durationMs={}",
                    nodeId, workflowInstanceId(state), elapsedMillis);
        });
    }

    /**
     * 节点异常后回收计时状态，向前端发布不含内部异常细节的 FAILED 事件，并把原始异常写入服务日志；
     * 本方法不吞掉 Graph 业务异常，也不负责把工作流实例收口为 FAILED。
     */
    @Override
    public void onError(String nodeId, Map<String, Object> state, Throwable ex, RunnableConfig config) {
        nodeDefinition(nodeId).ifPresent(definition -> {
            long elapsedMillis = elapsedMillis(state, nodeId);
            publishStage(definition, state, "FAILED", frontendErrorMessage(definition));
            log.warn("[Workflow] action=node-complete status=failed node={} workflowInstanceId={} durationMs={}",
                    nodeId, workflowInstanceId(state), elapsedMillis, ex);
        });
    }

    /** 记录 Graph 到达 END 的观测日志；最终 Memory、Checkpoint、实例状态和 COMPLETE 事件另行事务收口。 */
    @Override
    public void onComplete(String nodeId, Map<String, Object> state, RunnableConfig config) {
        log.info("[Workflow] action=graph-complete status=success workflowInstanceId={}", workflowInstanceId(state));
    }

    /**
     * 将一次节点生命周期转换成可靠 SSE 事件：先从 State（必要时回查实例表）解析工作流、会话和
     * fencing token，再按节点映射 stage/summary/review 协议，只构造脱敏状态字段，最后交给
     * WorkflowEventPublisher 校验当前执行权、分配单调 sequence、写入 OceanBase 事件事实表并尝试
     * 投递本机连接；其他 JVM 上的连接由数据库 Poller 补偿。关键标识缺失时跳过非关键观测事件。
     */
    private void publishStage(WorkflowNodeDefinition definition,
                              Map<String, Object> state,
                              String status,
                              String errorMessage) {
        String workflowInstanceId = workflowInstanceId(state);
        String conversationId = conversationId(state, workflowInstanceId);
        Long executionFenceToken = number(state.get(MainWorkflowStateKeys.EXECUTION_FENCE_TOKEN));
        if (workflowInstanceId == null || conversationId == null || executionFenceToken == null) {
            return;
        }
        WorkflowSseEventType eventType = switch (definition) {
            case SUMMARY -> WorkflowSseEventType.SUMMARY;
            case OUTPUT_REVIEW -> WorkflowSseEventType.REVIEW;
            default -> WorkflowSseEventType.STAGE;
        };
        Map<String, Object> data = errorMessage == null
                ? Map.of("status", status, "nodeName", definition.nodeName())
                : Map.of("status", status, "nodeName", definition.nodeName(), "message", errorMessage);
        workflowEventPublisher.publish(
                workflowInstanceId, conversationId, executionFenceToken, eventType, definition.code(), data);
    }

    /** 只观测主图已登记节点，忽略框架内部节点和未知扩展节点。 */
    private Optional<WorkflowNodeDefinition> nodeDefinition(String nodeId) {
        return Arrays.stream(WorkflowNodeDefinition.values())
                .filter(definition -> definition.code().equals(nodeId))
                .findFirst();
    }

    private String workflowInstanceId(Map<String, Object> state) {
        return value(state, MainWorkflowStateKeys.WORKFLOW_INSTANCE_ID, String.class).orElse(null);
    }

    /** 按请求、对齐上下文、实例表的优先级解析会话，兼容节点执行前后 State 内容不同。 */
    private String conversationId(Map<String, Object> state, String workflowInstanceId) {
        return value(state, MainWorkflowStateKeys.REQUEST, MainWorkflowRequest.class)
                .map(MainWorkflowRequest::conversationId)
                .or(() -> value(state, MainWorkflowStateKeys.ALIGNED_CONTEXT, AlignedWorkflowContext.class)
                        .map(AlignedWorkflowContext::conversationId))
                .or(() -> Optional.ofNullable(workflowInstanceId)
                        .map(workflowExecutionMapper::findInstance)
                        .map(instance -> instance.conversationId()))
                .orElse(null);
    }

    /** 删除本次节点计时记录并返回耗时；缺少 before 回调时返回 -1，避免残留并发状态。 */
    private long elapsedMillis(Map<String, Object> state, String nodeId) {
        Long started = nodeStartedNanos.remove(executionKey(state, nodeId));
        return started == null ? -1L : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private String executionKey(Map<String, Object> state, String nodeId) {
        Object executionId = state.getOrDefault(EXECUTION_ID_KEY, workflowInstanceId(state));
        return String.valueOf(executionId) + ':' + nodeId;
    }

    private Long number(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private <T> Optional<T> value(Map<String, Object> state, String key, Class<T> type) {
        Object value = state.get(key);
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }

    private String frontendErrorMessage(WorkflowNodeDefinition definition) {
        return "节点执行失败，请稍后重试或联系人工支持：" + definition.nodeName();
    }
}
