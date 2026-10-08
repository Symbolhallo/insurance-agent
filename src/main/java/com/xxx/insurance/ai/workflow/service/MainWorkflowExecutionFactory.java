package com.xxx.insurance.ai.workflow.service;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.insurance.ai.workflow.checkpoint.OceanBaseCheckpointSaver;
import com.xxx.insurance.ai.workflow.config.WorkflowExecutionConfig;
import com.xxx.insurance.ai.workflow.config.WorkflowLifecycleProperties;
import com.xxx.insurance.ai.workflow.model.MainWorkflowRequest;
import com.xxx.insurance.ai.workflow.model.MainWorkflowStateKeys;
import com.xxx.insurance.ai.workflow.model.WorkflowNodeDefinition;
import com.xxx.insurance.ai.workflow.model.WorkflowStepRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 创建主工作流运行所需的稳定标识、初始 State、Graph 配置和审计载荷。
 *
 * <p>这些操作没有业务状态迁移副作用，从应用编排服务中提取后，run/confirm/resume 主流程只保留
 * 生命周期动作；Checkpoint metadata、步骤预分配和 JSON 合同仍集中在一个确定性工厂中。</p>
 */
@Component
public class MainWorkflowExecutionFactory {

    private final ObjectMapper objectMapper;

    private final WorkflowLifecycleProperties lifecycleProperties;

    private final ThreadPoolTaskExecutor workflowDagTaskExecutor;

    /** 创建执行工厂，复用全局 JSON 配置、实例 owner 配置和 DAG 有界线程池。 */
    public MainWorkflowExecutionFactory(
            ObjectMapper objectMapper,
            WorkflowLifecycleProperties lifecycleProperties,
            @Qualifier(WorkflowExecutionConfig.WORKFLOW_DAG_TASK_EXECUTOR)
            ThreadPoolTaskExecutor workflowDagTaskExecutor) {
        this.objectMapper = objectMapper;
        this.lifecycleProperties = lifecycleProperties;
        this.workflowDagTaskExecutor = workflowDagTaskExecutor;
    }

    /** 生成符合数据库字段长度和日志规范的工作流实例编号。 */
    public String createWorkflowInstanceId() {
        return "wfi-" + UUID.randomUUID().toString().replace("-", "");
    }

    /** 构造 Main Graph 首次执行所需的最小 State，所有执行代次标识在启动时固定。 */
    public Map<String, Object> initialState(MainWorkflowRequest request,
                                            String workflowInstanceId,
                                            long executionFenceToken,
                                            Map<String, String> workflowStepIds,
                                            boolean tokenStreamingEnabled) {
        return Map.of(
                MainWorkflowStateKeys.REQUEST, request,
                MainWorkflowStateKeys.WORKFLOW_INSTANCE_ID, workflowInstanceId,
                MainWorkflowStateKeys.EXECUTION_FENCE_TOKEN, executionFenceToken,
                MainWorkflowStateKeys.WORKFLOW_STEP_IDS, workflowStepIds,
                MainWorkflowStateKeys.TOKEN_STREAMING_ENABLED, tokenStreamingEnabled);
    }

    /**
     * 创建可恢复 Graph 配置。metadata 固定携带 owner 和 fencing token，使 Saver 每次写入都能拒绝
     * 旧执行代次；workflowInstanceId 同时作为主图 Checkpoint threadId。
     */
    public RunnableConfig runnableConfig(String workflowInstanceId,
                                         String conversationId,
                                         long executionFenceToken) {
        return RunnableConfig.builder()
                .threadId(workflowInstanceId)
                .defaultParallelExecutor(workflowDagTaskExecutor)
                .addMetadata(OceanBaseCheckpointSaver.METADATA_WORKFLOW_INSTANCE_ID, workflowInstanceId)
                .addMetadata(OceanBaseCheckpointSaver.METADATA_CONVERSATION_ID, conversationId)
                .addMetadata(OceanBaseCheckpointSaver.METADATA_EXECUTION_OWNER,
                        lifecycleProperties.getInstanceId())
                .addMetadata(OceanBaseCheckpointSaver.METADATA_EXECUTION_FENCE_TOKEN,
                        executionFenceToken)
                .build();
    }

    /** 为当前 Graph 定义中的每个节点预分配数据库步骤编号。 */
    public Map<String, String> createWorkflowSteps() {
        Map<String, String> workflowStepIds = new LinkedHashMap<>();
        for (WorkflowNodeDefinition nodeDefinition : WorkflowNodeDefinition.values()) {
            workflowStepIds.put(nodeDefinition.code(), newWorkflowStepId());
        }
        return workflowStepIds;
    }

    /** 将全部可能执行的 Graph 节点以 PENDING 状态转换为步骤审计记录。 */
    public List<WorkflowStepRecord> workflowStepRecords(String workflowInstanceId,
                                                        Map<String, String> workflowStepIds,
                                                        String inputJson,
                                                        Instant createdAt) {
        return Arrays.stream(WorkflowNodeDefinition.values())
                .map(nodeDefinition -> new WorkflowStepRecord(
                        workflowStepIds.get(nodeDefinition.code()),
                        workflowInstanceId,
                        nodeDefinition.code(),
                        nodeDefinition.nodeName(),
                        nodeDefinition.type(),
                        nodeDefinition.target(),
                        "PENDING",
                        inputJson,
                        null,
                        createdAt))
                .toList();
    }

    /** 将工作流输入、状态或响应转换为数据库审计 JSON。 */
    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize workflow payload", ex);
        }
    }

    /** 将异常消息限制到数据库错误字段允许的最大长度。 */
    public String truncateErrorMessage(Exception exception) {
        if (exception == null || exception.getMessage() == null) {
            return null;
        }
        String message = exception.getMessage();
        return message.length() <= 1024 ? message : message.substring(0, 1024);
    }

    private String newWorkflowStepId() {
        return "wfs-" + UUID.randomUUID().toString().replace("-", "");
    }
}
