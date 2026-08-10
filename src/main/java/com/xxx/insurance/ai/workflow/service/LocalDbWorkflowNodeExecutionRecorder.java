package com.xxx.insurance.ai.workflow.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.workflow.config.MainWorkflowGraphConfig;
import com.xxx.insurance.ai.workflow.mapper.WorkflowExecutionMapper;
import com.xxx.insurance.ai.workflow.model.WorkflowNodeDefinition;
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

    public LocalDbWorkflowNodeExecutionRecorder(WorkflowExecutionMapper workflowExecutionMapper,
                                                ObjectMapper objectMapper) {
        this.workflowExecutionMapper = workflowExecutionMapper;
        this.objectMapper = objectMapper;
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
        Instant startedAt = Instant.now();
        if (workflowStepId != null) {
            workflowExecutionMapper.updateStepStarted(workflowStepId, startedAt);
        }
        try {
            Map<String, Object> result = nodeExecution.call();
            if (workflowStepId != null) {
                workflowExecutionMapper.updateStepResult(workflowStepId, "SUCCESS", toJson(result), null, Instant.now());
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
                        Instant.now());
            }
            throw ex;
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
        if (message.length() <= 1024) {
            return message;
        }
        return message.substring(0, 1024);
    }
}
