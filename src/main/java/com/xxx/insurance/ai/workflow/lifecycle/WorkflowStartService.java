package com.xxx.insurance.ai.workflow.lifecycle;

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

    /** 创建工作流启动事务服务；Mapper 的唯一约束和条件删除共同承担多实例幂等与会话互斥。 */
    public WorkflowStartService(WorkflowExecutionMapper workflowExecutionMapper) {
        this.workflowExecutionMapper = workflowExecutionMapper;
    }

    /**
     * 在一个事务中完成顶层工作流启动：先仅清理当前 conversation 已过期、无有效执行租约且无可恢复
     * Graph Thread 保护的失效锁，再插入 conversation 独占锁、RUNNING 实例和全部 PENDING 步骤。
     * conversation 主键与 (conversationId, requestId) 幂等唯一键是多 JVM 并发的最终防线；任一写入失败
     * 整体回滚，唯一键冲突统一转换为 WORKFLOW_REQUEST_CONFLICT，不会留下半初始化实例。
     */
    @Transactional(rollbackFor = Exception.class)
    public void start(WorkflowInstanceRecord instance, List<WorkflowStepRecord> steps) {
        try {
            // 启动链路 1：仅回收当前 conversation 已过期且不再需要保护的旧锁；
            // 仍有有效执行租约，或仍存在有效 Graph Thread 可恢复的工作流继续保持会话独占。
            workflowExecutionMapper.deleteExpiredInvalidConversationLock(
                    instance.conversationId(), instance.createdAt());
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
