package com.xxx.insurance.ai.workflow.service;

import com.alibaba.cloud.ai.graph.GraphLifecycleListener;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.xxx.insurance.ai.workflow.mapper.WorkflowExecutionMapper;
import com.xxx.insurance.ai.workflow.model.AlignedWorkflowContext;
import com.xxx.insurance.ai.workflow.model.MainWorkflowRequest;
import com.xxx.insurance.ai.workflow.model.MainWorkflowStateKeys;
import com.xxx.insurance.ai.workflow.model.WorkflowNodeDefinition;
import com.xxx.insurance.ai.workflow.model.WorkflowSseEventType;
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

    /** 创建 Main Graph 生命周期观测器。 */
    public MainWorkflowLifecycleListener(WorkflowExecutionMapper workflowExecutionMapper,
                                         WorkflowEventPublisher workflowEventPublisher) {
        this.workflowExecutionMapper = workflowExecutionMapper;
        this.workflowEventPublisher = workflowEventPublisher;
    }

    @Override
    public void onStart(String nodeId, Map<String, Object> state, RunnableConfig config) {
        log.info("[Workflow] action=graph-start status=running workflowInstanceId={}", workflowInstanceId(state));
    }

    @Override
    public void before(String nodeId, Map<String, Object> state, RunnableConfig config, Long curTime) {
        nodeDefinition(nodeId).ifPresent(definition -> {
            nodeStartedNanos.put(executionKey(state, nodeId), System.nanoTime());
            publishStage(definition, state, "RUNNING", null);
            log.info("[Workflow] action=node-start node={} workflowInstanceId={}",
                    nodeId, workflowInstanceId(state));
        });
    }

    @Override
    public void after(String nodeId, Map<String, Object> state, RunnableConfig config, Long curTime) {
        nodeDefinition(nodeId).ifPresent(definition -> {
            long elapsedMillis = elapsedMillis(state, nodeId);
            publishStage(definition, state, "SUCCESS", null);
            log.info("[Workflow] action=node-complete status=success node={} workflowInstanceId={} durationMs={}",
                    nodeId, workflowInstanceId(state), elapsedMillis);
        });
    }

    @Override
    public void onError(String nodeId, Map<String, Object> state, Throwable ex, RunnableConfig config) {
        nodeDefinition(nodeId).ifPresent(definition -> {
            long elapsedMillis = elapsedMillis(state, nodeId);
            publishStage(definition, state, "FAILED", frontendErrorMessage(definition));
            log.warn("[Workflow] action=node-complete status=failed node={} workflowInstanceId={} durationMs={}",
                    nodeId, workflowInstanceId(state), elapsedMillis, ex);
        });
    }

    @Override
    public void onComplete(String nodeId, Map<String, Object> state, RunnableConfig config) {
        log.info("[Workflow] action=graph-complete status=success workflowInstanceId={}", workflowInstanceId(state));
    }

    /** 发布不包含完整 State 的生命周期事件，Summary 和 Review 保持既有协议事件名。 */
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

    private Optional<WorkflowNodeDefinition> nodeDefinition(String nodeId) {
        return Arrays.stream(WorkflowNodeDefinition.values())
                .filter(definition -> definition.code().equals(nodeId))
                .findFirst();
    }

    private String workflowInstanceId(Map<String, Object> state) {
        return value(state, MainWorkflowStateKeys.WORKFLOW_INSTANCE_ID, String.class).orElse(null);
    }

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
