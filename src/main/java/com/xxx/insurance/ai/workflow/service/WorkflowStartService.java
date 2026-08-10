package com.xxx.insurance.ai.workflow.service;

import com.xxx.insurance.ai.workflow.mapper.WorkflowExecutionMapper;
import com.xxx.insurance.ai.workflow.model.WorkflowInstanceRecord;
import com.xxx.insurance.ai.workflow.model.WorkflowStepRecord;
import com.xxx.insurance.common.exception.BusinessException;
import com.xxx.insurance.common.exception.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 原子创建顶层工作流、会话执行锁和步骤审计记录。
 */
@Service
@Profile("local-db")
public class WorkflowStartService {

    private final WorkflowExecutionMapper workflowExecutionMapper;

    /** 创建工作流启动事务服务。 */
    public WorkflowStartService(WorkflowExecutionMapper workflowExecutionMapper) {
        this.workflowExecutionMapper = workflowExecutionMapper;
    }

    /**
     * 在同一个事务中占用 conversation 并创建实例。数据库主键和幂等唯一键是最终并发防线，
     * 因此多个 JVM 同时启动相同请求时也只有一个事务能够成功。
     */
    @Transactional(rollbackFor = Exception.class)
    public void start(WorkflowInstanceRecord instance, List<WorkflowStepRecord> steps) {
        try {
            workflowExecutionMapper.insertConversationLock(
                    instance.conversationId(),
                    instance.workflowInstanceId(),
                    instance.requestId(),
                    instance.leaseUntil(),
                    instance.createdAt());
            workflowExecutionMapper.insertInstance(instance);
            steps.forEach(workflowExecutionMapper::insertStep);
        }
        catch (DuplicateKeyException ex) {
            throw new BusinessException(
                    ErrorCode.WORKFLOW_REQUEST_CONFLICT,
                    "同一请求已受理，或当前会话仍有工作流正在执行");
        }
    }
}
