package com.xxx.insurance.ai.workflow.lifecycle;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.xxx.insurance.ai.workflow.model.WorkflowNodeDefinition;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Workflow 节点执行安全门禁。
 *
 * <p>该端口负责步骤状态 CAS、Execution Lease/Fence 校验和节点结果审计。通用日志、耗时与
 * Stage SSE 由 GraphLifecycleListener 承担，不能替代这里会中断旧执行分支的安全校验。</p>
 */
public interface WorkflowNodeExecutionGuard {

    Map<String, Object> execute(WorkflowNodeDefinition nodeDefinition,
                                OverAllState state,
                                Callable<Map<String, Object>> nodeExecution) throws Exception;
}
