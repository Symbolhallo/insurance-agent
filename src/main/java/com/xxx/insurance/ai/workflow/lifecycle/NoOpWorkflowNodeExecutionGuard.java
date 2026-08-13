package com.xxx.insurance.ai.workflow.lifecycle;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.xxx.insurance.ai.workflow.model.WorkflowNodeDefinition;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.Callable;

/** 默认 profile 不持久化步骤状态，只执行节点本身。 */
@Service
@Profile("!local-db")
public class NoOpWorkflowNodeExecutionGuard implements WorkflowNodeExecutionGuard {

    @Override
    public Map<String, Object> execute(WorkflowNodeDefinition nodeDefinition,
                                       OverAllState state,
                                       Callable<Map<String, Object>> nodeExecution) throws Exception {
        return nodeExecution.call();
    }
}
