package com.xxx.insurance.ai.agent.state;

import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.State;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NamespacedAgentStateStoreTests {

    @Test
    void isolatesSameUserAndSessionAcrossAgents() {
        InMemoryAgentStateStore delegate = new InMemoryAgentStateStore();
        NamespacedAgentStateStore productStore = new NamespacedAgentStateStore(delegate, "product-agent");
        NamespacedAgentStateStore policyStore = new NamespacedAgentStateStore(delegate, "policy-agent");

        productStore.save("user-1", "thread-1", "agent_state", new TestState("product"));
        policyStore.save("user-1", "thread-1", "agent_state", new TestState("policy"));

        assertThat(productStore.get("user-1", "thread-1", "agent_state", TestState.class))
                .contains(new TestState("product"));
        assertThat(policyStore.get("user-1", "thread-1", "agent_state", TestState.class))
                .contains(new TestState("policy"));
        assertThat(productStore.listSessionIds("user-1")).containsExactly("thread-1");
        assertThat(policyStore.listSessionIds("user-1")).containsExactly("thread-1");

        productStore.delete("user-1", "thread-1");

        assertThat(productStore.exists("user-1", "thread-1")).isFalse();
        assertThat(policyStore.exists("user-1", "thread-1")).isTrue();
    }

    private record TestState(String value) implements State {
    }
}

