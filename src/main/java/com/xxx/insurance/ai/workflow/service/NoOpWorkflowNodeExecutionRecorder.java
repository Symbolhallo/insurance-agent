package com.xxx.insurance.ai.workflow.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.xxx.insurance.ai.workflow.model.WorkflowNodeDefinition;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 默认节点执行记录器。
 */
@Service
@Profile("!local-db")
public class NoOpWorkflowNodeExecutionRecorder implements WorkflowNodeExecutionRecorder {

    @Override
    public Map<String, Object> record(WorkflowNodeDefinition nodeDefinition,
                                      OverAllState state,
                                      Callable<Map<String, Object>> nodeExecution) throws Exception {
        return nodeExecution.call();
    }
}
