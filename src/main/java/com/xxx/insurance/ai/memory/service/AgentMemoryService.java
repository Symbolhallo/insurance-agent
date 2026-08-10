package com.xxx.insurance.ai.memory.service;

import com.xxx.insurance.ai.memory.model.AgentMemoryExchange;
import com.xxx.insurance.ai.memory.model.AgentInvocationRecord;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * Agent 记忆协调服务。
 *
 * <p>该服务负责协调窗口记忆和长期记忆，避免业务 Agent 分别写多张表导致一致性问题。</p>
 */
public interface AgentMemoryService {

    /** 判断当前 profile 是否启用了持久化记忆能力。 */
    boolean isEnabled();

    /** 获取指定会话的窗口消息，供单 Agent 模型调用拼装上下文。 */
    List<Message> getHistory(String conversationId);

    /** 在一个事务中保存用户/助手消息、长期记忆和成功调用审计。 */
    void saveSuccessfulExchange(AgentMemoryExchange exchange, AgentInvocationRecord invocationRecord);

    /**
     * 只保存成功调用审计，不向 ChatMemory 和长期记忆追加消息。
     *
     * <p>多智能体 DAG 并行执行时使用该入口，避免多个子智能体并发修改同一会话历史；
     * 主工作流汇总完成后再通过 {@link #saveSuccessfulExchange(AgentMemoryExchange, AgentInvocationRecord)}
     * 一次性保存最终对话。</p>
     */
    void saveSuccessfulInvocation(AgentInvocationRecord invocationRecord);

    /** 只保存失败调用审计，不向 ChatMemory 添加失败回答。 */
    void saveFailedInvocation(AgentInvocationRecord invocationRecord);
}
