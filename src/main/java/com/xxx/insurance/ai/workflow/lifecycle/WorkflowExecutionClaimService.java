package com.xxx.insurance.ai.workflow.lifecycle;

import com.xxx.insurance.ai.workflow.config.WorkflowLifecycleProperties;
import com.xxx.insurance.ai.workflow.mapper.WorkflowExecutionMapper;
import com.xxx.insurance.ai.workflow.model.WorkflowInstanceExecutionView;
import com.xxx.insurance.ai.workflow.model.WorkflowResumeRequest;
import com.xxx.insurance.common.exception.BusinessException;
import com.xxx.insurance.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 主工作流确认与恢复执行权的状态机门面。
 *
 * <p>集中处理 WAITING_CONFIRM→CONFIRMING、RUNNING→RESUMING 的 CAS、owner/lease/fence 回读校验，
 * 以及尚未消费的确认 Claim 释放。Graph 调用仍由应用服务负责，旧执行者不能绕过这里取得可写代次。</p>
 */
@Service
@Profile("local-db")
public class WorkflowExecutionClaimService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutionClaimService.class);

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_WAITING_CONFIRM = "WAITING_CONFIRM";
    private static final String STATUS_CONFIRMING = "CONFIRMING";
    private static final String STATUS_RESUMING = "RESUMING";

    private final WorkflowExecutionMapper workflowExecutionMapper;
    private final WorkflowLifecycleProperties lifecycleProperties;

    /** 创建执行权门面，组合数据库 CAS 与当前 JVM owner/租约配置。 */
    public WorkflowExecutionClaimService(WorkflowExecutionMapper workflowExecutionMapper,
                                         WorkflowLifecycleProperties lifecycleProperties) {
        this.workflowExecutionMapper = workflowExecutionMapper;
        this.lifecycleProperties = lifecycleProperties;
    }

    /** 原子抢占等待确认实例并返回数据库生成的新 fencing token。 */
    public long claimProductConfirmation(String workflowInstanceId, String conversationId) {
        WorkflowInstanceExecutionView instance = workflowExecutionMapper.findInstance(workflowInstanceId);
        validateConversationAndStatus(instance, conversationId, STATUS_WAITING_CONFIRM);
        Instant now = Instant.now();
        if (workflowExecutionMapper.claimProductConfirmation(
                workflowInstanceId,
                conversationId,
                lifecycleProperties.getInstanceId(),
                now.plus(lifecycleProperties.getClaimLease()),
                now) != 1) {
            throw new BusinessException(
                    ErrorCode.WORKFLOW_STATE_CONFLICT,
                    "产品确认已被处理，请勿重复提交");
        }
        return requireOwnedFenceToken(workflowInstanceId, STATUS_CONFIRMING);
    }

    /** 回读已由当前入口抢占的确认实例，并拒绝过期 owner、lease 或 fencing token。 */
    public WorkflowInstanceExecutionView requireClaimedConfirmation(String workflowInstanceId,
                                                                    String conversationId,
                                                                    long executionFenceToken) {
        WorkflowInstanceExecutionView instance = workflowExecutionMapper.findInstance(workflowInstanceId);
        validateConversationAndStatus(instance, conversationId, STATUS_CONFIRMING);
        requireExpectedFence(instance, executionFenceToken);
        return instance;
    }

    /** 原子抢占可恢复的 RUNNING 实例，并返回原实例时间与新执行代次。 */
    public ResumeClaim claimResume(String workflowInstanceId, WorkflowResumeRequest request) {
        WorkflowInstanceExecutionView instance = workflowExecutionMapper.findInstance(workflowInstanceId);
        validateResumeInstance(instance, request);
        Instant claimTime = Instant.now();
        if (workflowExecutionMapper.claimResume(
                workflowInstanceId,
                request.conversationId(),
                lifecycleProperties.getInstanceId(),
                claimTime.plus(lifecycleProperties.getClaimLease()),
                claimTime) != 1) {
            throw new IllegalStateException("Workflow instance is not available for resume");
        }
        return new ResumeClaim(
                instance,
                requireOwnedFenceToken(workflowInstanceId, STATUS_RESUMING));
    }

    /** 尚未开始恢复时把确认 Claim 退回 WAITING_CONFIRM；CAS 失败不覆盖已转移的执行权。 */
    public void releaseProductConfirmationClaim(String workflowInstanceId,
                                                String conversationId,
                                                long executionFenceToken) {
        if (workflowExecutionMapper.releaseProductConfirmationClaim(
                workflowInstanceId,
                conversationId,
                lifecycleProperties.getInstanceId(),
                executionFenceToken,
                Instant.now()) != 1) {
            log.warn("[Workflow] action=release-confirmation-claim status=ignored workflowInstanceId={}",
                    workflowInstanceId);
        }
    }

    private void validateConversationAndStatus(WorkflowInstanceExecutionView instance,
                                               String conversationId,
                                               String expectedStatus) {
        if (instance == null) {
            throw new IllegalArgumentException("workflowInstanceId does not exist");
        }
        if (!instance.conversationId().equals(conversationId)) {
            throw new IllegalArgumentException("conversationId does not match workflow instance");
        }
        if (!expectedStatus.equals(instance.status())) {
            throw new BusinessException(
                    ErrorCode.WORKFLOW_STATE_CONFLICT,
                    "工作流当前不能处理产品确认，status=" + instance.status());
        }
    }

    private void validateResumeInstance(WorkflowInstanceExecutionView instance,
                                        WorkflowResumeRequest request) {
        if (instance == null) {
            throw new IllegalArgumentException("workflow instance does not exist");
        }
        if (!instance.conversationId().equals(request.conversationId())) {
            throw new IllegalArgumentException("conversationId does not match workflow instance");
        }
        if (!STATUS_RUNNING.equals(instance.status())) {
            throw new IllegalArgumentException("only RUNNING workflow instance can be resumed");
        }
    }

    private long requireOwnedFenceToken(String workflowInstanceId, String expectedStatus) {
        WorkflowInstanceExecutionView instance = workflowExecutionMapper.findInstance(workflowInstanceId);
        if (instance == null
                || !expectedStatus.equals(instance.status())
                || !lifecycleProperties.getInstanceId().equals(instance.executionOwner())
                || instance.leaseUntil() == null
                || !instance.leaseUntil().isAfter(Instant.now())) {
            throw new IllegalStateException("Workflow execution lease was lost after claim");
        }
        return instance.executionFenceToken();
    }

    private void requireExpectedFence(WorkflowInstanceExecutionView instance, long executionFenceToken) {
        if (instance.executionFenceToken() != executionFenceToken
                || !lifecycleProperties.getInstanceId().equals(instance.executionOwner())
                || instance.leaseUntil() == null
                || !instance.leaseUntil().isAfter(Instant.now())) {
            throw new IllegalStateException("Workflow execution fencing token is stale");
        }
    }

    /** 主动恢复抢占结果。 */
    public record ResumeClaim(WorkflowInstanceExecutionView instance, long executionFenceToken) {
    }
}
