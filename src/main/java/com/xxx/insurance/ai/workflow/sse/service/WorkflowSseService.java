package com.xxx.insurance.ai.workflow.sse.service;

import com.xxx.insurance.ai.workflow.config.WorkflowExecutionConfig;
import com.xxx.insurance.ai.workflow.model.MainWorkflowRequest;
import com.xxx.insurance.ai.workflow.service.MainWorkflowService;
import com.xxx.insurance.product.model.ProductConfirmationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 主工作流 SSE 应用服务，负责新运行的后台提交和历史事件重连。
 */
@Service
@Profile("local-db")
public class WorkflowSseService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowSseService.class);

    private final MainWorkflowService mainWorkflowService;

    private final LocalDbWorkflowSseEventService eventService;

    private final ThreadPoolTaskExecutor taskExecutor;

    /** 创建 SSE 应用服务并注入同步工作流、事件存储和隔离线程池。 */
    public WorkflowSseService(MainWorkflowService mainWorkflowService,
                              LocalDbWorkflowSseEventService eventService,
                              @Qualifier(WorkflowExecutionConfig.WORKFLOW_SSE_TASK_EXECUTOR)
                              ThreadPoolTaskExecutor taskExecutor) {
        this.mainWorkflowService = mainWorkflowService;
        this.eventService = eventService;
        this.taskExecutor = taskExecutor;
    }

    /**
     * 先注册 SSE 连接，再使用同一 workflowInstanceId 在后台执行 Main Graph。
     */
    public SseEmitter start(MainWorkflowRequest request) {
        // 主工作流链路 2：预分配实例编号并先注册连接，再把 Graph 提交到隔离线程池，避免首事件丢失。
        String workflowInstanceId = mainWorkflowService.createWorkflowInstanceId();
        SseEmitter emitter = eventService.subscribeNewRun(workflowInstanceId);
        try {
            taskExecutor.execute(() -> execute(workflowInstanceId, request));
        }
        catch (RuntimeException ex) {
            eventService.failNewRun(workflowInstanceId, ex);
            throw ex;
        }
        return emitter;
    }

    /** 根据 Last-Event-ID 重放事件，并在实例仍运行时继续订阅实时事件。 */
    public SseEmitter reconnect(String workflowInstanceId, String lastEventId) {
        return eventService.reconnect(workflowInstanceId, lastEventId);
    }

    /**
     * 先原子抢占 WAITING_CONFIRM 实例，再建立订阅并在后台流式恢复原 Graph。
     */
    public SseEmitter confirmProducts(String workflowInstanceId,
                                      ProductConfirmationRequest request,
                                      String lastEventId) {
        // 主工作流链路 8：原子抢占确认权，随后重放/订阅，再异步恢复 Checkpoint，避免恢复事件早于连接。
        long executionFenceToken = mainWorkflowService.claimProductConfirmation(
                workflowInstanceId, request.conversationId());
        try {
            SseEmitter emitter = eventService.subscribeConfirmationResume(workflowInstanceId, lastEventId);
            taskExecutor.execute(() -> executeConfirmation(workflowInstanceId, request, executionFenceToken));
            return emitter;
        }
        catch (RuntimeException ex) {
            eventService.completeSubscribers(workflowInstanceId);
            mainWorkflowService.releaseProductConfirmationClaim(
                    workflowInstanceId, request.conversationId(), executionFenceToken);
            throw ex;
        }
    }

    /** 在 SSE 专属线程中执行原有 MainWorkflowService，复用完整 Graph 与持久化链路。 */
    private void execute(String workflowInstanceId, MainWorkflowRequest request) {
        try {
            mainWorkflowService.run(workflowInstanceId, request, true);
        }
        catch (Exception ex) {
            // LocalDbMainWorkflowService 会持久化 error 事件；这里兜底处理实例创建前失败。
            eventService.failNewRun(workflowInstanceId, ex);
            log.error("[Workflow] action=sse-run status=failed workflowInstanceId={}", workflowInstanceId, ex);
        }
    }

    /** 从人工确认 Checkpoint 恢复，并保持后续前置节点、子智能体和 Summary 的模型流。 */
    private void executeConfirmation(String workflowInstanceId,
                                     ProductConfirmationRequest request,
                                     long executionFenceToken) {
        try {
            mainWorkflowService.confirmClaimedProducts(
                    workflowInstanceId, request, true, executionFenceToken);
        }
        catch (Exception ex) {
            eventService.failSubscribedRun(
                    workflowInstanceId, request.conversationId(), "产品确认后工作流恢复失败");
            log.error("[Workflow] action=sse-confirm-resume status=failed workflowInstanceId={}",
                    workflowInstanceId, ex);
        }
    }
}
