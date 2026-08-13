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
 * 主工作流 SSE 应用门面。
 *
 * <p>负责把 HTTP 长连接建立与耗时 Graph 执行解耦：先注册 SseEmitter，再将新运行或人工确认恢复
 * 提交到专属有界线程池；重连时从 OceanBase 事实表按 Last-Event-ID 重放，并继续跟随实时事件。
 * Graph、Checkpoint、事务收口仍由 MainWorkflowService 负责。</p>
 */
@Service
@Profile("local-db")
public class WorkflowSseService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowSseService.class);

    private final MainWorkflowService mainWorkflowService;

    private final LocalDbWorkflowSseEventService eventService;

    private final ThreadPoolTaskExecutor taskExecutor;

    /** 创建 SSE 应用服务，组合主工作流门面、OceanBase 事件交付服务和拒绝静默排队的隔离线程池。 */
    public WorkflowSseService(MainWorkflowService mainWorkflowService,
                              LocalDbWorkflowSseEventService eventService,
                              @Qualifier(WorkflowExecutionConfig.WORKFLOW_SSE_TASK_EXECUTOR)
                              ThreadPoolTaskExecutor taskExecutor) {
        this.mainWorkflowService = mainWorkflowService;
        this.eventService = eventService;
        this.taskExecutor = taskExecutor;
    }

    /**
     * 启动一次流式工作流：预分配 workflowInstanceId，使用该编号先注册 SseEmitter，再提交后台 Graph，
     * 从而保证 START/首 Token 产生时连接已经存在。线程池拒绝或提交异常时立即清理临时订阅并把异常
     * 交还 HTTP 层，不留下尚未创建实例的悬空连接。
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

    /**
     * 校验 Last-Event-ID 与 workflowInstanceId，按 sequence 重放尚未过期的 OceanBase 事件；
     * 若实例仍可产生事件，则在同一实例锁内注册实时游标，避免历史重放与后续事件之间出现缺口。
     */
    public SseEmitter reconnect(String workflowInstanceId, String lastEventId) {
        return eventService.reconnect(workflowInstanceId, lastEventId);
    }

    /**
     * 恢复人工确认链路：先以数据库 CAS 将 WAITING_CONFIRM 抢占为 CONFIRMING 并取得新的 fencing token，
     * 再重放遗漏事件、建立第二段 SSE 订阅，最后异步写入确认产品并从 Checkpoint 恢复 Graph。订阅或线程
     * 提交失败时关闭连接并按 owner/token 释放抢占，使用户可以安全重试且不会从同一 Checkpoint 分叉。
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

    /**
     * 在 SSE 专属线程执行完整 MainWorkflowService：创建会话锁/实例/步骤、运行可恢复 Graph、发布模型流，
     * 并在成功、人工暂停或失败路径执行原有事务收口；这里只兜底处理实例落库前等外围异常并清理连接。
     */
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

    /**
     * 使用抢占时取得的 fencing token 校验恢复权，保存标准确认产品、更新 Checkpoint State，并继续执行
     * 上下文对齐、意图识别、Planner、DAG 和 Summary 流；失败时发布脱敏 error 并结束当前恢复连接。
     */
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
