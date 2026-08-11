package com.xxx.insurance.ai.workflow.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.workflow.config.MainWorkflowGraphConfig;
import com.xxx.insurance.ai.workflow.config.WorkflowLifecycleProperties;
import com.xxx.insurance.ai.workflow.mapper.WorkflowExecutionMapper;
import com.xxx.insurance.ai.workflow.model.WorkflowNodeDefinition;
import com.xxx.insurance.ai.workflow.model.AlignedWorkflowContext;
import com.xxx.insurance.ai.workflow.model.MainWorkflowRequest;
import com.xxx.insurance.ai.workflow.model.MainWorkflowStateKeys;
import com.xxx.insurance.ai.workflow.model.WorkflowSseEventType;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 本地数据库节点执行记录器。
 */
@Service
@Profile("local-db")
public class LocalDbWorkflowNodeExecutionRecorder implements WorkflowNodeExecutionRecorder {

    private final WorkflowExecutionMapper workflowExecutionMapper;

    private final ObjectMapper objectMapper;

    private final WorkflowEventPublisher workflowEventPublisher;

    private final WorkflowLifecycleProperties lifecycleProperties;

    public LocalDbWorkflowNodeExecutionRecorder(WorkflowExecutionMapper workflowExecutionMapper,
                                                ObjectMapper objectMapper,
                                                WorkflowEventPublisher workflowEventPublisher,
                                                WorkflowLifecycleProperties lifecycleProperties) {
        this.workflowExecutionMapper = workflowExecutionMapper;
        this.objectMapper = objectMapper;
        this.workflowEventPublisher = workflowEventPublisher;
        this.lifecycleProperties = lifecycleProperties;
    }

    /**
     * 在不侵入业务 Node 的前提下记录节点开始、成功输出或失败原因。
     *
     * <p>MainWorkflowGraphConfig 使用该方法包装每个 NodeAction；Graph State 中的 stepId
     * 将运行时节点与 ai_workflow_step 对齐。异常记录后继续抛出，由 Graph 和工作流服务处理终态。</p>
     */
    @Override
    public Map<String, Object> record(WorkflowNodeDefinition nodeDefinition,
                                      OverAllState state,
                                      Callable<Map<String, Object>> nodeExecution) throws Exception {
        String workflowStepId = MainWorkflowGraphConfig.workflowStepIds(state).get(nodeDefinition.code());
        long executionFenceToken = executionFenceToken(state);
        Instant startedAt = Instant.now();
        if (workflowStepId != null) {
            requireLeaseWrite(workflowExecutionMapper.updateStepStarted(
                    workflowStepId, lifecycleProperties.getInstanceId(), executionFenceToken, startedAt));
        }
        publishStage(nodeDefinition, state, "RUNNING", null);
        try {
            Map<String, Object> result = nodeExecution.call();
            if (workflowStepId != null) {
                requireLeaseWrite(workflowExecutionMapper.updateStepResult(
                        workflowStepId, "SUCCESS", toJson(result), null,
                        lifecycleProperties.getInstanceId(), executionFenceToken, Instant.now()));
            }
            publishStage(nodeDefinition, state, "SUCCESS", null);
            return result;
        }
        catch (Exception ex) {
            if (workflowStepId != null) {
                workflowExecutionMapper.updateStepResult(
                        workflowStepId,
                        "FAILED",
                        null,
                        truncateErrorMessage(ex),
                        lifecycleProperties.getInstanceId(),
                        executionFenceToken,
                        Instant.now());
            }
            publishStage(nodeDefinition, state, "FAILED", frontendErrorMessage(nodeDefinition));
            throw ex;
        }
    }

    /** 发布不包含完整 State 的节点阶段事件，Summary 和 Review 使用各自协议事件名。 */
    private void publishStage(WorkflowNodeDefinition nodeDefinition,
                              OverAllState state,
                              String status,
                              String errorMessage) {
        String workflowInstanceId = state.value(MainWorkflowStateKeys.WORKFLOW_INSTANCE_ID, String.class).orElse(null);
        String conversationId = conversationId(state, workflowInstanceId);
        if (workflowInstanceId == null || conversationId == null) {
            return;
        }
        WorkflowSseEventType eventType = switch (nodeDefinition) {
            case SUMMARY -> WorkflowSseEventType.SUMMARY;
            case OUTPUT_REVIEW -> WorkflowSseEventType.REVIEW;
            default -> WorkflowSseEventType.STAGE;
        };
        Map<String, Object> data = errorMessage == null
                ? Map.of("status", status, "nodeName", nodeDefinition.nodeName())
                : Map.of("status", status, "nodeName", nodeDefinition.nodeName(), "message", errorMessage);
        workflowEventPublisher.publish(
                workflowInstanceId, conversationId, executionFenceToken(state),
                eventType, nodeDefinition.code(), data);
    }

    /** 从持久化 Graph State 获取本次执行固定的 fencing token。 */
    private long executionFenceToken(OverAllState state) {
        return state.value(MainWorkflowStateKeys.EXECUTION_FENCE_TOKEN, Number.class)
                .map(Number::longValue)
                .orElseThrow(() -> new IllegalStateException("Missing execution fence token in graph state"));
    }

    /** 步骤审计同样必须拒绝已失去租约的旧 Graph 分支。 */
    private void requireLeaseWrite(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("Workflow execution lease was lost while recording node state");
        }
    }

    /** 从初始请求或已对齐上下文读取当前会话编号。 */
    private String conversationId(OverAllState state, String workflowInstanceId) {
        return state.value(MainWorkflowStateKeys.REQUEST, MainWorkflowRequest.class)
                .map(MainWorkflowRequest::conversationId)
                .or(() -> state.value(MainWorkflowStateKeys.ALIGNED_CONTEXT, AlignedWorkflowContext.class)
                        .map(AlignedWorkflowContext::conversationId))
                .or(() -> java.util.Optional.ofNullable(workflowInstanceId)
                        .map(workflowExecutionMapper::findInstance)
                        .map(instance -> instance.conversationId()))
                .orElse(null);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize workflow node payload", ex);
        }
    }

    private String truncateErrorMessage(Exception exception) {
        if (exception == null || exception.getMessage() == null) {
            return null;
        }
        String message = exception.getMessage();
        if (message.length() <= 1024) {
            return message;
        }
        return message.substring(0, 1024);
    }

    /** 返回不包含上游响应、请求编号、凭证或堆栈的前端错误描述。 */
    private String frontendErrorMessage(WorkflowNodeDefinition nodeDefinition) {
        return "节点执行失败，请稍后重试或联系人工支持：" + nodeDefinition.nodeName();
    }
}
