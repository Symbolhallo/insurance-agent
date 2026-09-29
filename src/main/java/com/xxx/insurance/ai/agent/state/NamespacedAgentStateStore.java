package com.xxx.insurance.ai.agent.state;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** 为共享 AgentStateStore 增加 Agent 级 session 命名空间，防止相同 threadId 的状态互相覆盖。 */
public class NamespacedAgentStateStore implements AgentStateStore {

    private static final String SEPARATOR = "::";

    private final AgentStateStore delegate;

    private final String prefix;

    public NamespacedAgentStateStore(AgentStateStore delegate, String agentId) {
        this.delegate = delegate;
        this.prefix = requireText(agentId) + SEPARATOR;
    }

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        delegate.save(userId, namespace(sessionId), key, value);
    }

    @Override
    public boolean supportsVersioning() {
        return delegate.supportsVersioning();
    }

    @Override
    public <T extends State> VersionedState<T> getVersioned(
            String userId, String sessionId, String key, Class<T> type) {
        return delegate.getVersioned(userId, namespace(sessionId), key, type);
    }

    @Override
    public long saveIfVersion(
            String userId, String sessionId, String key, State value, long expectedVersion) {
        return delegate.saveIfVersion(userId, namespace(sessionId), key, value, expectedVersion);
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        delegate.save(userId, namespace(sessionId), key, values);
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        return delegate.get(userId, namespace(sessionId), key, type);
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> itemType) {
        return delegate.getList(userId, namespace(sessionId), key, itemType);
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return delegate.exists(userId, namespace(sessionId));
    }

    @Override
    public void delete(String userId, String sessionId) {
        delegate.delete(userId, namespace(sessionId));
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        delegate.delete(userId, namespace(sessionId), key);
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        return delegate.listSessionIds(userId).stream()
                .filter(sessionId -> sessionId.startsWith(prefix))
                .map(sessionId -> sessionId.substring(prefix.length()))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void close() {
        // Shared delegate lifecycle belongs to Spring, not to an individual Agent wrapper.
    }

    private String namespace(String sessionId) {
        return prefix + requireText(sessionId);
    }

    private String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("AgentScope namespace value must not be blank");
        }
        return value;
    }
}

