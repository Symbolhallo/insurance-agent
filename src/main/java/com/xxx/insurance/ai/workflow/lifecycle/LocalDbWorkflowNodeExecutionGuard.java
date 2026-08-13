package com.xxx.insurance.ai.workflow.lifecycle;

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

    /** 创建节点安全门禁，组合步骤状态 Mapper、结果 JSON 审计和当前 JVM execution owner 配置。 */
    public LocalDbWorkflowNodeExecutionGuard(WorkflowExecutionMapper workflowExecutionMapper,
                                             ObjectMapper objectMapper,
                                             WorkflowLifecycleProperties lifecycleProperties) {
        this.workflowExecutionMapper = workflowExecutionMapper;
        this.objectMapper = objectMapper;
        this.lifecycleProperties = lifecycleProperties;
    }

    /**
     * 在真实 NodeAction 外围实施强制执行门禁：从持久化 State 读取本次固定 fencing token，以当前 owner、
     * token 和有效 lease 把步骤 CAS 为 RUNNING；节点成功后序列化增量结果并 CAS 为 SUCCESS，失败后尽力
     * 写入截断错误并原样抛出。启动或成功审计 CAS 失败会立即阻止旧 Graph 分支继续写业务 State；该安全
     * 语义不能迁移到异常会被框架隔离的 GraphLifecycleListener。
     */
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

    /** 将节点增量结果序列化为步骤审计 JSON；序列化失败按系统异常终止当前节点。 */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize workflow node payload", ex);
        }
    }

    /** 将原始异常消息限制到数据库字段长度，不把堆栈写入业务表。 */
    private String truncateErrorMessage(Exception exception) {
        if (exception == null || exception.getMessage() == null) {
            return null;
        }
        String message = exception.getMessage();
        return message.length() <= 1024 ? message : message.substring(0, 1024);
    }
}
