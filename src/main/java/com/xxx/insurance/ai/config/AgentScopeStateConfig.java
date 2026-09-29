package com.xxx.insurance.ai.config;

import com.xxx.insurance.ai.agent.state.AgentScopeStateMapper;
import com.xxx.insurance.ai.agent.state.OceanBaseAgentStateStore;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 为 HarnessAgent 选择与运行 profile 匹配的 AgentScope 状态后端。 */
@Configuration
public class AgentScopeStateConfig {

    /** 默认和测试 profile 使用线程安全内存状态，避免 Harness 回退到用户目录 JSON 文件。 */
    @Bean
    @Profile("!local-db")
    public AgentStateStore inMemoryAgentStateStore() {
        return new InMemoryAgentStateStore();
    }

    /** local-db profile 使用 OceanBase CAS 状态仓库，支持重启恢复和多实例会话共享。 */
    @Bean
    @Profile("local-db")
    public AgentStateStore oceanBaseAgentStateStore(AgentScopeStateMapper mapper) {
        return new OceanBaseAgentStateStore(mapper);
    }
}

