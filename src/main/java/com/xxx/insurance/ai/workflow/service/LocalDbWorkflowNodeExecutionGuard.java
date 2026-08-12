package com.xxx.insurance.ai.workflow.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.workflow.config.MainWorkflowGraphConfig;
import com.xxx.insurance.ai.workflow.config.WorkflowLifecycleProperties;
import com.xxx.insurance.ai.workflow.mapper.WorkflowExecutionMapper;
import com.xxx.insurance.ai.workflow.model.MainWorkflowStateKeys;
import com.xxx.insurance.ai.workflow.model.WorkflowNodeDefinition;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * OceanBase 节点步骤状态和执行权安全门禁。
 *
 * <p>GraphLifecycleListener 的异常会被框架捕获，因此数据库状态机、Lease 和 Fence Token
 * 校验必须保留在节点调用链内。旧 owner 或旧 fencing token 写入失败时，本类直接拒绝继续执行。</p>
 */
@Service
@Profile("local-db")
public class LocalDbWorkflowNodeExecutionGuard implements WorkflowNodeExecutionGuard {

    private final WorkflowExecutionMapper workflowExecutionMapper;

    private final ObjectMapper objectMapper;

    private final WorkflowLifecycleProperties lifecycleProperties;

    /** 创建节点安全门禁。 */
    public LocalDbWorkflowNodeExecutionGuard(WorkflowExecutionMapper workflowExecutionMapper,
                                             ObjectMapper objectMapper,
                                             WorkflowLifecycleProperties lifecycleProperties) {
        this.workflowExecutionMapper = workflowExecutionMapper;
        this.objectMapper = objectMapper;
        this.lifecycleProperties = lifecycleProperties;
    }

    /** 在节点调用链内执行步骤状态 CAS、结果审计和 Lease/Fence 校验。 */
    @Override
    public Map<String, Object> execute(WorkflowNodeDefinition nodeDefinition,
                                       OverAllState state,
                                       Callable<Map<String, Object>> nodeExecution) throws Exception {
        String workflowStepId = MainWorkflowGraphConfig.workflowStepIds(state).get(nodeDefinition.code());
        long executionFenceToken = executionFenceToken(state);
        Instant startedAt = Instant.now();
        if (workflowStepId != null) {
            requireLeaseWrite(workflowExecutionMapper.updateStepStarted(
                    workflowStepId, lifecycleProperties.getInstanceId(), executionFenceToken, startedAt));
        }
        try {
            Map<String, Object> result = nodeExecution.call();
            if (workflowStepId != null) {
                requireLeaseWrite(workflowExecutionMapper.updateStepResult(
                        workflowStepId, "SUCCESS", toJson(result), null,
                        lifecycleProperties.getInstanceId(), executionFenceToken, Instant.now()));
            }
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
            throw ex;
        }
    }

    /** 从持久化 Graph State 获取本次执行固定的 fencing token。 */
    private long executionFenceToken(OverAllState state) {
        return state.value(MainWorkflowStateKeys.EXECUTION_FENCE_TOKEN, Number.class)
                .map(Number::longValue)
                .orElseThrow(() -> new IllegalStateException("Missing execution fence token in graph state"));
    }

    /** 步骤审计必须拒绝已失去租约的旧 Graph 分支。 */
    private void requireLeaseWrite(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("Workflow execution lease was lost while recording node state");
        }
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
        return message.length() <= 1024 ? message : message.substring(0, 1024);
    }
}
