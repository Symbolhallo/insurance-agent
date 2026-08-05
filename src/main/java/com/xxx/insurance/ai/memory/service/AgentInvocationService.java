package com.xxx.insurance.ai.memory.service;

import com.xxx.insurance.ai.memory.model.AgentInvocationRecord;

/**
 * Agent 调用流水持久化服务。
 *
 * <p>该服务只负责写入调用观测数据，不读取或拼接模型上下文。业务侧统一通过
 * {@link AgentMemoryService} 协调调用流水、窗口记忆和长期记忆的事务边界。</p>
 */
public interface AgentInvocationService {

    void save(AgentInvocationRecord record);
}
