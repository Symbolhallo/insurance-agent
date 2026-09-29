package com.xxx.insurance.ai.agent.state;

/**
 * AgentScope 状态表读取模型。
 *
 * @param statePayload State JSON 或 State 列表 JSON
 * @param listValue 是否为列表载荷
 * @param stateVersion 当前乐观锁版本
 */
public record AgentScopeStateRecord(
        String statePayload,
        boolean listValue,
        long stateVersion) {
}

