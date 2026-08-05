package com.xxx.insurance.ai.memory.service;

import com.xxx.insurance.ai.memory.model.AgentInvocationRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 默认 Agent 调用流水服务。
 */
@Service
@Profile("!local-db")
public class NoOpAgentInvocationService implements AgentInvocationService {

    @Override
    public void save(AgentInvocationRecord record) {
        // Default profile is intentionally stateless.
    }
}
