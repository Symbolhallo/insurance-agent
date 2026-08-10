package com.xxx.insurance.ai.workflow.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.xxx.insurance.ai.workflow.model.WorkflowNodeDefinition;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Workflow 节点执行记录器。
 */
public interface WorkflowNodeExecutionRecorder {

    Map<String, Object> record(WorkflowNodeDefinition nodeDefinition,
                               OverAllState state,
                               Callable<Map<String, Object>> nodeExecution) throws Exception;
}
