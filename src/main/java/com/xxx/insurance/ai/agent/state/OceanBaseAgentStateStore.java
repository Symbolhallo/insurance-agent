package com.xxx.insurance.ai.agent.state;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AgentScope {@link AgentStateStore} 的 OceanBase 实现。
 *
 * <p>单值和列表均以完整 JSON 替换保存；单值额外支持数据库条件更新，供 Harness 在同一
 * session 并发访问时执行 CAS。该状态只承载 AgentScope 内部上下文，不替代业务 ChatMemory、
 * 长期记忆、Graph Checkpoint 或工作流 Lease/Fence。</p>
 */
public class OceanBaseAgentStateStore implements AgentStateStore {

    private static final String ANONYMOUS_USER = "__anon__";

    private final AgentScopeStateMapper mapper;

    private final JsonCodec jsonCodec = JsonUtils.getJsonCodec();

    public OceanBaseAgentStateStore(AgentScopeStateMapper mapper) {
        this.mapper = mapper;
    }

    /** 无条件覆盖单值状态。 */
    @Override
    public void save(String userId, String sessionId, String key, State value) {
        mapper.upsert(normalizeUser(userId), requireText(sessionId, "sessionId"),
                requireText(key, "key"), jsonCodec.toJson(value), false);
    }

    @Override
    public boolean supportsVersioning() {
        return true;
    }

    /** 返回状态和数据库版本；不存在的键使用 AgentScope 约定版本 0。 */
    @Override
    public <T extends State> VersionedState<T> getVersioned(
            String userId, String sessionId, String key, Class<T> type) {
        AgentScopeStateRecord record = findRecord(userId, sessionId, key);
        if (record == null) {
            return new VersionedState<>(null, 0L);
        }
        ensureValueKind(record, false, key);
        return new VersionedState<>(jsonCodec.fromJson(record.statePayload(), type), record.stateVersion());
    }

    /** 使用 INSERT IGNORE 或带版本条件的 UPDATE 实现跨实例 CAS。 */
    @Override
    public long saveIfVersion(String userId,
                              String sessionId,
                              String key,
                              State value,
                              long expectedVersion) {
        if (expectedVersion == UNVERSIONED) {
            save(userId, sessionId, key, value);
            return getVersioned(userId, sessionId, key, value.getClass()).version();
        }
        String normalizedUser = normalizeUser(userId);
        String normalizedSession = requireText(sessionId, "sessionId");
        String normalizedKey = requireText(key, "key");
        String payload = jsonCodec.toJson(value);
        int affected = expectedVersion == 0
                ? mapper.insertIfAbsent(normalizedUser, normalizedSession, normalizedKey, payload)
                : mapper.updateIfVersion(
                        normalizedUser, normalizedSession, normalizedKey, payload, expectedVersion);
        return affected == 1 ? expectedVersion + 1 : UNVERSIONED;
    }

    /** 完整覆盖列表状态；AgentScope 调用方每次传入完整列表。 */
    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        mapper.upsert(normalizeUser(userId), requireText(sessionId, "sessionId"),
                requireText(key, "key"), jsonCodec.toJson(values), true);
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        AgentScopeStateRecord record = findRecord(userId, sessionId, key);
        if (record == null) {
            return Optional.empty();
        }
        ensureValueKind(record, false, key);
        return Optional.of(jsonCodec.fromJson(record.statePayload(), type));
    }

    /** 先解析通用 JSON 数组，再逐项按调用方要求的 State 类型转换。 */
    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> itemType) {
        AgentScopeStateRecord record = findRecord(userId, sessionId, key);
        if (record == null) {
            return List.of();
        }
        ensureValueKind(record, true, key);
        List<?> rawValues = jsonCodec.fromJson(record.statePayload(), List.class);
        List<T> values = new ArrayList<>(rawValues.size());
        for (Object rawValue : rawValues) {
            values.add(jsonCodec.convertValue(rawValue, itemType));
        }
        return List.copyOf(values);
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return mapper.countSession(normalizeUser(userId), requireText(sessionId, "sessionId")) > 0;
    }

    @Override
    public void delete(String userId, String sessionId) {
        mapper.deleteSession(normalizeUser(userId), requireText(sessionId, "sessionId"));
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        mapper.deleteState(normalizeUser(userId), requireText(sessionId, "sessionId"), requireText(key, "key"));
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        return Set.copyOf(mapper.findSessionIds(normalizeUser(userId)));
    }

    private AgentScopeStateRecord findRecord(String userId, String sessionId, String key) {
        return mapper.find(
                normalizeUser(userId), requireText(sessionId, "sessionId"), requireText(key, "key"));
    }

    private void ensureValueKind(AgentScopeStateRecord record, boolean expectedList, String key) {
        if (record.listValue() != expectedList) {
            throw new IllegalStateException("AgentScope state kind mismatch for key: " + key);
        }
    }

    private String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? ANONYMOUS_USER : userId;
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

